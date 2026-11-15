package com.dnd.model.character;

import com.dnd.chat.SessionChat;
import com.dnd.data.CampaignRepositories;
import com.dnd.data.EntityDescriptions;
import com.dnd.data.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Persistent, host-authoritative proposals. Character unlock receipts make approval retry-safe. */
public final class UpgradeRequests {
    public record Proposal(String revision, Map<String, Integer> points, String node,
                           Map<String, String> spells) { }
    public record Ability(String id, String name, int score) { }
    public record SpellOption(String id, String name, String description) { }
    public record SpellChoice(String primary, String name, List<SpellOption> options) { }
    public record Choice(String id, String name, String description, String blocked,
                         List<SpellChoice> spells) {
        @Override public String toString() { return name; }
    }
    public record Options(String revision, int fromLevel, int budget, List<Ability> abilities,
                          String summary, List<Choice> choices) { }
    public record Request(String id, String character, String name, String status, long created,
                          Proposal submitted, Proposal decision, String details) { }
    public record Inbox(List<Request> requests, Options options) { }
    private record Candidate(PlayerCharacter before, PlayerCharacter after) { }
    private static final Map<Path, UpgradeRequests> SERVICES = new HashMap<>();
    private final Path root;
    private final ObjectMapper mapper;

    public static synchronized UpgradeRequests at(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return SERVICES.computeIfAbsent(normalized, UpgradeRequests::new);
    }

    public UpgradeRequests(Path root) {
        this.root = root;
        mapper = JsonMappers.forCampaign(root);
    }

    public synchronized Inbox execute(String actor, SessionChat.Command command) {
        if (command == null || command.id() == null || !command.id().matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid upgrade request identifier.");
        boolean gm = SessionChat.HOST.equals(actor);
        String character = gm ? command.conversation() : character(actor);
        List<Request> requests = load();
        Options options = null;
        switch (Objects.toString(command.operation(), "")) {
            case "upgrade-options" -> options = options(character, decode(command.text()));
            case "upgrade-submit" -> {
                if (gm) throw new SecurityException("Only a player can submit an upgrade request.");
                Proposal proposal = required(command.text());
                Request previous = requests.stream().filter(r -> r.id().equals(command.id())).findFirst().orElse(null);
                if (previous != null) {
                    if (!previous.character().equals(character) || !previous.submitted().equals(proposal))
                        throw new IllegalArgumentException("An upgrade request identifier was reused.");
                } else {
                    if (requests.stream().anyMatch(r -> r.character().equals(character) && r.status().equals("pending")))
                        throw new IllegalStateException("This character already has a pending upgrade request.");
                    if (requests.size() >= 2000) throw new IllegalStateException("The upgrade inbox is full.");
                    PlayerCharacter draft = candidate(character, proposal).after();
                    if (receipt(draft, command.id())) throw new IllegalStateException("This request was already applied.");
                    Options preview = options(character, proposal);
                    String details = describe(preview, proposal);
                    requests.add(new Request(command.id(), character, draft.getName(), "pending",
                        System.currentTimeMillis(), proposal, null, details));
                    save(requests);
                }
            }
            case "upgrade-accept", "upgrade-decline" -> {
                if (!gm) throw new SecurityException("Only the hosting GM can decide an upgrade request.");
                int index = -1;
                for (int i = 0; i < requests.size(); i++)
                    if (requests.get(i).id().equals(command.conversation())) { index = i; break; }
                if (index < 0) throw new IllegalArgumentException("Upgrade request not found.");
                Request request = requests.get(index);
                boolean accept = command.operation().equals("upgrade-accept");
                Proposal decision = accept ? required(command.text()) : null;
                String status = accept ? "accepted" : "declined";
                if (!request.status().equals("pending")) {
                    if (!request.status().equals(status) || accept && !Objects.equals(request.decision(), decision))
                        throw new IllegalStateException("This request has already been decided.");
                } else if (!accept) {
                    requests.set(index, decided(request, status, null, request.details()));
                    save(requests);
                } else {
                    // A review may change choices, but cannot rebase a stale request onto another level.
                    if (!Objects.equals(request.submitted().revision(), decision.revision()))
                        throw new IllegalArgumentException("The character changed. Decline this request and ask for a new one.");
                    Candidate candidate = candidate(request.character(), decision);
                    PlayerCharacter draft = candidate.after();
                    draft.getUpgrades().getUnlocks().getLast().setSourceRequestId(request.id());
                    String details = describe(options(request.character(), decision), decision);
                    requests.set(index, decided(request, "applying", decision, details));
                    save(requests);
                    new CampaignRepositories(root).players().updateIfUnchanged(candidate.before(), draft);
                    requests.set(index, decided(request, status, decision, details));
                    save(requests);
                }
            }
            case "poll" -> { }
            default -> throw new IllegalArgumentException("Unknown upgrade operation.");
        }
        List<Request> visible = requests.stream().filter(r -> gm || r.character().equals(character)).toList();
        return new Inbox(gm ? visible : visible.subList(Math.max(0, visible.size() - 3), visible.size()), options);
    }

    public synchronized Inbox inbox(String actor) {
        return execute(actor, SessionChat.Command.poll(null));
    }

    private String character(String actor) {
        if (actor == null || !actor.startsWith("pc:")) throw new SecurityException("Choose your own character.");
        return actor.substring(3);
    }

    public synchronized Options options(String character, Proposal proposal) {
        CampaignRepositories repos = new CampaignRepositories(root);
        PlayerCharacter pc = player(repos, character);
        CharacterClass cls = repos.classes().getById(pc.getClassId());
        if (!Progression.canLevelUp(pc)) throw new IllegalStateException("Level up requires 100 XP and a level below 20.");
        if (cls == null || pc.getStats() == null) throw new IllegalStateException("The GM must configure this character's class and stats.");
        List<Ability> abilities = pc.getStats().schema().getAbilities().stream()
            .map(a -> new Ability(a.id(), a.name(), pc.getStats().score(a.id()))).toList();
        int capacity = abilities.stream().mapToInt(a -> Math.max(0, 20 - a.score())).sum();
        int budget = Progression.plan(pc, cls).abilityScoreImprovement ? Math.min(2, capacity) : 0;
        String revision = revision(pc, cls);
        PlayerCharacter draft = copy(pc);
        int oldCon = Progression.modifier(pc.getStats().getConstitution());
        if (proposal != null) {
            if (!revision.equals(proposal.revision())) throw new IllegalArgumentException("The character or class tree changed. Start a new proposal.");
            applyPoints(draft, proposal.points(), budget);
        }
        UpgradeRules.initialize(draft, cls);
        Progression.LevelUpPlan plan = Progression.plan(draft, cls);
        plan.hitPointGain += (Progression.modifier(draft.getStats().getConstitution()) - oldCon) * pc.getLevel();
        Progression.apply(draft, plan);
        UpgradeRules.initialize(draft, cls);
        EntityDescriptions descriptions = EntityDescriptions.of(repos);
        List<Choice> choices = new ArrayList<>();
        for (UpgradeTree.Node node : cls.getUpgradeTree().getNodes()) {
            List<SpellChoice> spells = new ArrayList<>();
            String blocked = UpgradeRules.blockedReason(draft, cls, node);
            if (blocked.isBlank()) {
                for (var choice : UpgradeRules.spellChoices(draft, node.getReward(), repos.spells()::getById)) {
                    var candidates = choice.alternatives().isEmpty() ? List.of(choice.primary()) : choice.alternatives();
                    spells.add(new SpellChoice(choice.primary().getId(), choice.primary().getName(), candidates.stream()
                        .map(s -> new SpellOption(s.getId(), s.getName(), descriptions.spellSummary(s) + "\n" + descriptions.spellBody(s))).toList()));
                }
            }
            String branch = cls.getUpgradeTree().getBranches().stream()
                .filter(b -> Objects.equals(b.getId(), node.getBranchId())).map(UpgradeTree.Branch::getName).findFirst().orElse("");
            choices.add(new Choice(node.getId(), branch + " / " + node.getName(),
                Objects.toString(node.getDescription(), "") + "\n" + rewards(node, repos),
                blocked, spells));
        }
        String summary = "Level " + pc.getLevel() + " -> " + plan.toLevel + "\nHP +" + plan.hitPointGain
            + "; mana +" + plan.manaGain + "; proficiency +" + plan.proficiencyBonus
            + "\nAbility points: " + budget + " (standard ASI levels; maximum score 20)."
            + "\nSpell slot maxima: " + Arrays.toString(plan.slotsAfter)
            + "\n" + plan.features.stream().map(f -> f.getName() + ": " + f.getDescription())
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\nXP resets to 0 only after GM approval. Current injuries are not fully healed.";
        return new Options(revision, pc.getLevel(), budget, abilities, summary, List.copyOf(choices));
    }

    private Candidate candidate(String character, Proposal proposal) {
        Options options = options(character, proposal);
        Choice choice = options.choices().stream().filter(c -> Objects.equals(c.id(), proposal.node())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Choose an upgrade from your class tree."));
        if (!choice.blocked().isBlank()) throw new IllegalArgumentException(choice.blocked());
        CampaignRepositories repos = new CampaignRepositories(root);
        PlayerCharacter before = player(repos, character);
        PlayerCharacter draft = copy(before);
        CharacterClass cls = repos.classes().getById(draft.getClassId());
        if (!revision(before, cls).equals(proposal.revision()))
            throw new IllegalArgumentException("The character changed. Start a new proposal.");
        int oldCon = Progression.modifier(draft.getStats().getConstitution());
        applyPoints(draft, proposal.points(), options.budget());
        UpgradeRules.initialize(draft, cls);
        Progression.LevelUpPlan plan = Progression.plan(draft, cls);
        plan.hitPointGain += (Progression.modifier(draft.getStats().getConstitution()) - oldCon) * draft.getLevel();
        Progression.apply(draft, plan);
        UpgradeRules.unlock(draft, cls, proposal.node(), repos.spells()::getById,
            proposal.spells() == null ? Map.of() : proposal.spells());
        return new Candidate(before, draft);
    }

    private void applyPoints(PlayerCharacter pc, Map<String, Integer> points, int budget) {
        if (points == null) throw new IllegalArgumentException("Choose your ability points.");
        Set<String> ids = new HashSet<>();
        pc.getStats().schema().getAbilities().forEach(a -> ids.add(a.id()));
        int used = 0;
        for (var entry : points.entrySet()) {
            Integer delta = entry.getValue();
            if (!ids.contains(entry.getKey()) || delta == null || delta < 0 || delta > 2
                    || delta > 0 && pc.getStats().score(entry.getKey()) + delta > 20)
                throw new IllegalArgumentException("Ability points must be positive increases within the budget and score cap of 20.");
            used += delta;
        }
        if (used != budget) throw new IllegalArgumentException("Allocate exactly " + budget + " ability points.");
        points.forEach((id, delta) -> pc.getStats().score(id, pc.getStats().score(id) + delta));
    }

    private String revision(PlayerCharacter pc, CharacterClass cls) {
        try {
            var state = mapper.createObjectNode();
            state.put("level", pc.getLevel());
            state.set("stats", mapper.valueToTree(pc.getStats()));
            state.set("upgrades", mapper.valueToTree(pc.getUpgrades()));
            state.set("spells", mapper.valueToTree(pc.getSpells()));
            state.set("class", mapper.valueToTree(cls));
            state.put("subclass", pc.getSubclass());
            state.put("maxHP", pc.getMaxHitPoints());
            state.put("maxMana", pc.getMaxMana());
            state.set("castingResource", mapper.valueToTree(pc.getCastingResource()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(state)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private PlayerCharacter player(CampaignRepositories repos, String id) {
        PlayerCharacter pc = id == null ? null : repos.players().getById(id);
        if (pc == null) throw new IllegalArgumentException("Character not found.");
        return pc;
    }

    private PlayerCharacter copy(PlayerCharacter pc) {
        return mapper.convertValue(pc, PlayerCharacter.class);
    }

    private Proposal decode(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > 12000) throw new IllegalArgumentException("Upgrade request too large.");
        try {
            return mapper.readerFor(Proposal.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(json);
        }
        catch (IOException e) { throw new IllegalArgumentException("Invalid upgrade choices.", e); }
    }

    private Proposal required(String json) {
        Proposal result = decode(json);
        if (result == null) throw new IllegalArgumentException("Upgrade choices are required.");
        return result;
    }

    private boolean receipt(PlayerCharacter pc, String id) {
        return pc.getUpgrades() != null && pc.getUpgrades().getUnlocks().stream()
            .anyMatch(u -> id.equals(u.getSourceRequestId()));
    }

    private Request decided(Request r, String status, Proposal proposal, String details) {
        return new Request(r.id(), r.character(), r.name(), status, r.created(), r.submitted(), proposal, details);
    }

    private List<Request> load() {
        Path file = root.resolve("upgrade-requests.json");
        try {
            List<Request> requests = Files.exists(file) ? mapper.readValue(Files.readAllBytes(file), new TypeReference<>() { })
                : new ArrayList<>();
            boolean changed = false;
            CampaignRepositories repos = new CampaignRepositories(root);
            for (int i = 0; i < requests.size(); i++) {
                Request r = requests.get(i);
                if (!r.status().equals("applying")) continue;
                PlayerCharacter pc = repos.players().getById(r.character());
                requests.set(i, decided(r, pc != null && receipt(pc, r.id()) ? "accepted" : "pending", r.decision(), r.details()));
                changed = true;
            }
            if (changed) save(requests);
            return requests;
        } catch (IOException e) { throw new UncheckedIOException("Cannot read the upgrade inbox.", e); }
    }

    private void save(List<Request> requests) {
        try {
            Path temporary = Files.createTempFile(root, ".almerion-upgrade-", ".tmp");
            try {
                Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(requests));
                try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary, root.resolve("upgrade-requests.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new UncheckedIOException("Cannot save the upgrade inbox.", e); }
    }

    private String describe(Options options, Proposal proposal) {
        StringBuilder text = new StringBuilder(options.summary()).append("\n\nAbility choices:\n");
        options.abilities().forEach(a -> {
            int delta = proposal.points().getOrDefault(a.id(), 0);
            if (delta > 0) text.append(a.name()).append(": ").append(a.score()).append(" -> ").append(a.score() + delta).append("\n");
        });
        Choice choice = options.choices().stream().filter(c -> c.id().equals(proposal.node())).findFirst().orElseThrow();
        text.append("\n").append(choice.name()).append("\n").append(choice.description());
        if (proposal.spells() != null) for (SpellChoice spell : choice.spells()) {
            String selected = proposal.spells().get(spell.primary());
            spell.options().stream().filter(s -> s.id().equals(selected)).findFirst()
                .ifPresent(s -> text.append("\nSpell choice: ").append(s.name()).append("\n").append(s.description()));
        }
        return text.toString();
    }

    private String rewards(UpgradeTree.Node node, CampaignRepositories repos) {
        UpgradeTree.Reward r = node.getReward();
        List<String> lines = new ArrayList<>();
        int[] values = {r.getHitPoints(), r.getMana(), r.getArmorClass(), r.getAttackBonus(),
            r.getBasicAttackDamage(), r.getRageDamage(), r.getSpeed(), r.getAbilityIncrease()};
        String[] names = {"HP", "Mana", "Armor class", "Attack rolls", "Basic attack damage", "Rage damage", "Speed", Objects.toString(r.getAbilityId(), "Ability")};
        for (int i = 0; i < values.length; i++) if (values[i] != 0) lines.add(names[i] + " +" + values[i]);
        for (ClassFeature feature : r.getFeatures()) {
            if (!Objects.equals(feature.getDescription(), node.getDescription()))
                lines.add(feature.getName() + ": " + feature.getDescription());
            else if (!Objects.equals(feature.getName(), node.getName())) lines.add(feature.getName());
            if (feature.getUsesPerRest() > 0) lines.add("Uses: " + feature.getUsesPerRest() + " per "
                + (feature.isShortRestRecharge() ? "short or long rest." : "long rest."));
        }
        EntityDescriptions descriptions = EntityDescriptions.of(repos);
        for (String id : r.getSpellIds()) {
            var spell = repos.spells().getById(id);
            if (spell == null) throw new IllegalStateException("The GM must repair the missing tree spell: " + id);
            lines.add(spell.getName() + ": " + descriptions.spellSummary(spell) + "\n" + descriptions.spellBody(spell));
        }
        return String.join("\n", lines);
    }
}
