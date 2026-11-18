package com.dnd.data;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic JSON-file-backed CRUD repository. All I/O failures surface as the
 * unchecked {@link DataAccessException} (consistent with {@link IdHandler}
 * and campaign storage) so callers aren't forced to handle checked
 * IOException; invalid usage (missing/duplicate id) continues to throw
 * {@link IllegalArgumentException}.
 */
public class JsonRepository<T, W> {
    private static final java.util.concurrent.ConcurrentMap<Path, Object> WRITE_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path filePath;
    private final ObjectMapper mapper;
    private final Supplier<W> wrapperFactory;
    private final Class<W> wrapperClass;
    private final Function<W, List<T>> listGetter;
    private final BiConsumer<W, List<T>> listSetter;
    private final Function<T, String> idGetter;

    public JsonRepository(Path filePath,
                          ObjectMapper mapper,
                          Supplier<W> wrapperFactory,
                          Class<W> wrapperClass,
                          Function<W, List<T>> listGetter,
                          BiConsumer<W, List<T>> listSetter,
                          Function<T, String> idGetter) {
        this.filePath = filePath;
        this.mapper = mapper;
        this.wrapperFactory = wrapperFactory;
        this.wrapperClass = wrapperClass;
        this.listGetter = listGetter;
        this.listSetter = listSetter;
        this.idGetter = idGetter;
    }

    public List<T> list() {
        W wrapper = readWrapper();
        List<T> items = new ArrayList<>(listGetter.apply(wrapper));
        items.sort(Comparator.comparing(JsonRepository::displayLabel, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    /**
     * Every catalogue entity's natural sort key: its display name where available, its
     * {@code toString()} otherwise. Sorting every read here - the single choke point every
     * list, dropdown and card row in the app reads through - means the whole app is always
     * alphabetical without each screen having to remember to sort it itself.
     */
    private static String displayLabel(Object entity) {
        if (entity == null) {
            return "";
        }
        try {
            Object name = entity.getClass().getMethod("getName").invoke(entity);
            if (name != null && !name.toString().isBlank()) {
                return name.toString();
            }
        } catch (ReflectiveOperationException ignored) {
            // no getName() (or it returned nothing usable) - fall back to toString() below.
        }
        String text = entity.toString();
        return text == null ? "" : text;
    }

    public T getById(String id) {
        if (id == null) {
            return null;
        }
        for (T item : list()) {
            if (id.equals(idGetter.apply(item))) {
                return item;
            }
        }
        return null;
    }

    public void add(T item) {
        synchronized (writeLock()) {
            String id = requireId(item);
            W wrapper = readWrapper();
            List<T> items = listGetter.apply(wrapper);
            if (findIndex(items, id) != -1) {
                throw new IllegalArgumentException("Duplicate id: " + id);
            }
            items.add(item);
            writeWrapper(wrapper);
        }
    }

    public void update(T item) {
        synchronized (writeLock()) {
            String id = requireId(item);
            W wrapper = readWrapper();
            List<T> items = listGetter.apply(wrapper);
            int index = findIndex(items, id);
            if (index == -1) {
                throw new IllegalArgumentException("Unknown id: " + id);
            }
            checkUpgradeReceipts(items.get(index), item);
            items.set(index, item);
            writeWrapper(wrapper);
        }
    }

    public void save(T entity) {
        synchronized (writeLock()) {
            String id = requireId(entity);
            W wrapper = readWrapper();
            List<T> items = listGetter.apply(wrapper);
            int index = findIndex(items, id);
            if (index == -1) {
                items.add(entity);
            } else {
                checkUpgradeReceipts(items.get(index), entity);
                items.set(index, entity);
            }
            writeWrapper(wrapper);
        }
    }

    /** Refuses to overwrite combat/XP changes made while an approval was being prepared. */
    public void updateIfUnchanged(T expected, T replacement) {
        synchronized (writeLock()) {
            String id = requireId(expected);
            if (!id.equals(requireId(replacement))) throw new IllegalArgumentException("Entity identity changed.");
            W wrapper = readWrapper();
            List<T> items = listGetter.apply(wrapper);
            int index = findIndex(items, id);
            if (index < 0 || !mapper.valueToTree(expected).equals(mapper.valueToTree(items.get(index))))
                throw new IllegalStateException("The character changed while saving. Review and retry.");
            checkUpgradeReceipts(items.get(index), replacement);
            items.set(index, replacement);
            writeWrapper(wrapper);
        }
    }

    private Object writeLock() {
        return WRITE_LOCKS.computeIfAbsent(filePath.toAbsolutePath().normalize(), p -> new Object());
    }

    boolean changeCatalogue(java.util.function.Predicate<W> change) {
        synchronized (writeLock()) {
            W wrapper = readWrapper();
            if (!change.test(wrapper)) return false;
            writeWrapper(wrapper);
            return true;
        }
    }

    private void checkUpgradeReceipts(T previous, T replacement) {
        if (!(previous instanceof com.dnd.model.character.PlayerCharacter oldPc)
                || !(replacement instanceof com.dnd.model.character.PlayerCharacter newPc)
                || oldPc.getUpgrades() == null) return;
        var saved = newPc.getUpgrades() == null ? java.util.Set.<String>of()
            : newPc.getUpgrades().getUnlocks().stream().map(com.dnd.model.character.UpgradeProgress.Unlock::getSourceRequestId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (oldPc.getUpgrades().getUnlocks().stream().map(com.dnd.model.character.UpgradeProgress.Unlock::getSourceRequestId)
                .filter(java.util.Objects::nonNull).anyMatch(id -> !saved.contains(id)))
            throw new IllegalStateException("This character was upgraded in another window. Reopen the character before editing.");
    }

    public boolean delete(String id) {
        synchronized (writeLock()) {
            if (id == null) {
                return false;
            }
            ensureFileExists();
            String revision = fileRevision();
            W wrapper = readWrapper();
            List<T> items = listGetter.apply(wrapper);
            int index = findIndex(items, id);
            if (index == -1) {
                return false;
            }
            CampaignRestorePoints.beforeEntityDeletion(filePath);
            if (!revision.equals(fileRevision())) {
                throw new IllegalStateException("The catalogue changed while preparing deletion. Refresh and try again.");
            }
            items.remove(index);
            writeWrapper(wrapper);
            return true;
        }
    }

    private String fileRevision() {
        try {
            return com.dnd.sync.ManualFiles.hash(filePath);
        } catch (IOException e) {
            throw new DataAccessException("Failed to read " + filePath, e);
        }
    }

    private int findIndex(List<T> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(idGetter.apply(items.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private String requireId(T item) {
        if (item == null) {
            throw new IllegalArgumentException("Item cannot be null.");
        }
        String id = idGetter.apply(item);
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Item id is required.");
        }
        return id;
    }

    private W readWrapper() {
        ensureFileExists();
        W wrapper;
        try {
            wrapper = mapper.readValue(filePath.toFile(), wrapperClass);
        } catch (IOException e) {
            throw new DataAccessException("Failed to read " + filePath, e);
        }
        List<T> items = listGetter.apply(wrapper);
        if (items == null) {
            items = new ArrayList<>();
            listSetter.accept(wrapper, items);
        }
        return wrapper;
    }

    private void writeWrapper(W wrapper) {
        try {
            Files.createDirectories(filePath.getParent());
            OperationHistory.writeCatalogue(filePath, mapper.writeValueAsBytes(wrapper));
        } catch (IOException e) {
            throw new DataAccessException("Failed to write " + filePath, e);
        }
    }

    private void ensureFileExists() {
        if (Files.exists(filePath)) {
            return;
        }
        try {
            Files.createDirectories(filePath.getParent());
            W wrapper = wrapperFactory.get();
            listSetter.accept(wrapper, new ArrayList<>());
            mapper.writeValue(filePath.toFile(), wrapper);
        } catch (IOException e) {
            throw new DataAccessException("Failed to initialize " + filePath, e);
        }
    }
}
