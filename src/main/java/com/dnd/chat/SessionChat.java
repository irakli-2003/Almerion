package com.dnd.chat;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.PlayerCharacter;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** Host-mediated, memory-only conversations. The host can monitor, but not impersonate players. */
public final class SessionChat implements AutoCloseable {
    public static final String HOST = "gm";
    public static final int MESSAGE_LIMIT = 500;
    public static final String DISCLOSURE = "The current hosting GM can read every session conversation, including private and group chats. Chats are erased when hosting ends; only the latest 500 messages per conversation are kept.";
    public record Peer(String id, String name) { }
    public record Command(String id, String operation, String conversation, List<String> recipients, String title, String text) {
        public static Command poll(String conversation) { return new Command(UUID.randomUUID().toString(), "poll", conversation, List.of(), "", ""); }
    }
    public record Message(long sequence, long at, String sender, String text) { }
    public record Conversation(String id, String title, List<String> members, int unread) { }
    public record View(String peer, List<Peer> peers, List<Conversation> conversations, String selected, List<Message> messages) { }
    public interface Connection extends AutoCloseable {
        View execute(Command command);
        @Override default void close() { }
    }
    private static final Map<Path, SessionChat> HOSTED = new HashMap<>();
    private static final class ThreadState {
        final String id = UUID.randomUUID().toString();
        final String title;
        final List<String> members;
        final Deque<Message> messages = new ArrayDeque<>();
        final Map<String, Long> read = new HashMap<>();
        ThreadState(String title, List<String> members) { this.title = title; this.members = members; }
    }
    private final Supplier<List<Peer>> roster;
    private final Map<String, ThreadState> threads = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, Command>> accepted = new HashMap<>();
    private final Map<String, String> created = new HashMap<>();
    private long sequence;
    private boolean closed;
    private int references;

    public SessionChat(Supplier<List<Peer>> roster) { this.roster = Objects.requireNonNull(roster); }
    public static String peerId(PlayerCharacter pc) { return "pc:" + pc.getId(); }

    public static synchronized SessionChat acquire(Path campaign) {
        Path root = campaign.toAbsolutePath().normalize();
        SessionChat chat = HOSTED.computeIfAbsent(root, p -> new SessionChat(() ->
            new CampaignRepositories(p).players().list().stream().map(pc -> new Peer(peerId(pc),
                pc.getName() == null ? pc.getId() : pc.getName())).toList()));
        chat.references++;
        return chat;
    }

    public static synchronized SessionChat hosted(Path campaign) {
        return campaign == null ? null : HOSTED.get(campaign.toAbsolutePath().normalize());
    }

    public static synchronized void release(Path campaign, SessionChat expected) {
        Path root = campaign.toAbsolutePath().normalize();
        if (HOSTED.get(root) == expected && --expected.references == 0) {
            HOSTED.remove(root);
            expected.close();
        }
    }

    public Connection connection(String peer) { return command -> execute(peer, command); }

    public synchronized View execute(String actor, Command command) {
        Map<String, Peer> peers = peers();
        if (!peers.containsKey(actor)) throw new SecurityException("This character is no longer in the session.");
        if (command == null || command.id() == null || !command.id().matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid chat request.");
        String selected = command.conversation();
        if ("poll".equals(command.operation())) return view(actor, selected, peers);
        var previous = accepted.computeIfAbsent(actor, p -> new LinkedHashMap<>());
        Command duplicate = previous.get(command.id());
        if (duplicate != null) {
            if (!duplicate.equals(command)) throw new IllegalArgumentException("A chat request identifier was reused.");
            return view(actor, created.getOrDefault(actor + "/" + command.id(), selected), peers);
        }
        if ("create".equals(command.operation())) {
            if (command.recipients() == null || command.recipients().isEmpty() || command.recipients().size() > 32) {
                throw new IllegalArgumentException("Choose between 1 and 32 chat recipients.");
            }
            TreeSet<String> members = new TreeSet<>(command.recipients());
            members.add(actor);
            if (members.size() < 2 || !peers.keySet().containsAll(members)) throw new IllegalArgumentException("Choose valid chat recipients.");
            String title = command.title() == null ? "" : command.title().strip();
            if (title.length() > 100) throw new IllegalArgumentException("Chat titles are limited to 100 characters.");
            ThreadState thread = members.size() == 2 ? threads.values().stream()
                .filter(t -> t.members.equals(List.copyOf(members))).findFirst().orElse(null) : null;
            if (thread == null) {
                if (threads.size() >= 100) throw new IllegalStateException("This session has reached its 100-conversation safety limit.");
                if (title.isEmpty()) title = members.stream().map(p -> peers.get(p).name()).collect(java.util.stream.Collectors.joining(", "));
                thread = new ThreadState(title, List.copyOf(members));
                threads.put(thread.id, thread);
            }
            selected = thread.id;
            created.put(actor + "/" + command.id(), selected);
        } else if ("send".equals(command.operation())) {
            ThreadState thread = threads.get(selected);
            if (thread == null || !thread.members.contains(actor)) throw new SecurityException("You are not a participant in this conversation.");
            String text = command.text() == null ? "" : command.text().strip();
            if (text.isEmpty() || text.length() > 2000) throw new IllegalArgumentException("Messages must contain between 1 and 2000 characters.");
            thread.messages.addLast(new Message(++sequence, System.currentTimeMillis(), actor, text));
            while (thread.messages.size() > MESSAGE_LIMIT) thread.messages.removeFirst();
        } else throw new IllegalArgumentException("Unknown chat operation.");
        previous.put(command.id(), command);
        while (previous.size() > 500) {
            String expired = previous.keySet().iterator().next();
            previous.remove(expired);
            created.remove(actor + "/" + expired);
        }
        return view(actor, selected, peers);
    }

    private Map<String, Peer> peers() {
        if (closed) throw new IllegalStateException("The chat session has ended.");
        Map<String, Peer> result = new LinkedHashMap<>();
        result.put(HOST, new Peer(HOST, "Game Master"));
        for (Peer peer : roster.get()) {
            if (peer == null || peer.id() == null || peer.id().equals(HOST) || result.putIfAbsent(peer.id(), peer) != null) {
                throw new IllegalStateException("Ambiguous chat participant.");
            }
        }
        return result;
    }

    private View view(String actor, String selected, Map<String, Peer> peers) {
        ThreadState chosen = selected == null ? null : threads.get(selected);
        if (selected != null && (chosen == null || !HOST.equals(actor) && !chosen.members.contains(actor))) {
            throw new SecurityException("This conversation is not available to you.");
        }
        if (chosen != null) chosen.read.put(actor, sequence);
        List<Conversation> visible = new ArrayList<>();
        for (ThreadState thread : threads.values()) if (HOST.equals(actor) || thread.members.contains(actor)) {
            long read = thread.read.getOrDefault(actor, 0L);
            int unread = (int) thread.messages.stream().filter(m -> m.sequence() > read && !m.sender().equals(actor)).count();
            visible.add(new Conversation(thread.id, thread.title, thread.members, unread));
        }
        return new View(actor, List.copyOf(peers.values()), List.copyOf(visible), selected,
            chosen == null ? List.of() : List.copyOf(chosen.messages));
    }

    @Override public synchronized void close() {
        closed = true;
        threads.clear();
        accepted.clear();
        created.clear();
    }
}
