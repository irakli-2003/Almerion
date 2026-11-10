package com.dnd.model.character;

import com.dnd.model.magic.Spell;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class UpgradeRules {
    private UpgradeRules() { }

    public static UpgradeTree starter(CharacterClass cls) {
        UpgradeTree tree = new UpgradeTree();
        String key = Objects.toString(cls.getId(), "");
        String[] names = switch (key) {
            case "barbarian" -> new String[]{"Berserker", "Iron Fist"};
            case "wizard" -> new String[]{"Spellweaver", "Arcane Warden"};
            case "ranger" -> new String[]{"Wild Stalker", "Forest Guardian"};
            case "bard" -> new String[]{"Virtuoso", "Inspiring Voice"};
            case "cleric" -> new String[]{"Sacred Flame", "Sanctuary"};
            case "rogue" -> new String[]{"Shadowblade", "Escape Artist"};
            case "fighter" -> new String[]{"Weapon Master", "Vanguard"};
            case "artificer" -> new String[]{"Inventor", "Field Engineer"};
            case "druid" -> new String[]{"Wildspeaker", "Grove Keeper"};
            case "monk" -> new String[]{"Open Palm", "Inner Stillness"};
            case "paladin" -> new String[]{"Oathblade", "Protector"};
            case "sorcerer" -> new String[]{"Spellshaper", "Arcane Resilience"};
            case "warlock" -> new String[]{"Pact Weaver", "Veiled Guardian"};
            default -> new String[]{"Focused Training", "Resilient Training"};
        };
        boolean caster = cls.getSpellcasting() != null
            && cls.getSpellcasting() != com.dnd.model.magic.SpellcastingType.NONE;
        for (int branch = 0; branch < 2; branch++) {
            var b = new UpgradeTree.Branch();
            b.setId(branch == 0 ? "potency" : "resilience"); b.setName(names[branch]);
            tree.getBranches().add(b);
            for (int tier = 0; tier < 4; tier++) {
                var n = new UpgradeTree.Node();
                n.setId(b.getId() + "-" + (tier + 1)); n.setBranchId(b.getId());
                n.setName(names[branch] + " " + (tier + 1));
                n.setMinimumLevel(new int[]{2, 6, 10, 14}[tier]);
                n.setDescription("A step along the " + names[branch] + " path. The GM can replace these starter rewards.");
                if (tier > 0) n.getPrerequisites().add(b.getId() + "-" + tier);
                var r = n.getReward();
                if (branch == 1) {
                    switch (tier) {
                        case 0 -> r.setHitPoints(3);
                        case 1 -> r.setArmorClass(1);
                        case 2 -> r.setSpeed(5);
                        default -> r.setHitPoints(5);
                    }
                } else if (tier == 0 && "barbarian".equals(key)) {
                    r.setRageDamage(1);
                } else if (caster && !cls.getDefaultSpells().isEmpty() && tier == 0) {
                    r.getSpellIds().add(cls.getDefaultSpells().getFirst());
                } else {
                    switch (tier) {
                        case 0 -> r.setBasicAttackDamage(1);
                        case 1 -> r.setAttackBonus(1);
                        case 2 -> r.setSpeed(5);
                        default -> r.setBasicAttackDamage(1);
                    }
                }
                if ("barbarian".equals(key) && branch == 1 && tier == 0) {
                    r.setHitPoints(0); r.setBasicAttackDamage(1);
                }
                tree.getNodes().add(n);
            }
        }
        return tree;
    }

    public static void validate(UpgradeTree tree) {
        Set<String> branches = new HashSet<>();
        for (var b : tree.getBranches()) {
            require(b != null && id(b.getId()) && text(b.getName()), "Branches need a stable ID and a name.");
            require(branches.add(b.getId()), "Duplicate branch ID: " + b.getId());
        }
        Map<String, UpgradeTree.Node> nodes = new LinkedHashMap<>();
        for (var n : tree.getNodes()) {
            require(n != null && id(n.getId()) && text(n.getName()), "Nodes need a stable ID and a name.");
            require(nodes.putIfAbsent(n.getId(), n) == null, "Duplicate node ID: " + n.getId());
            require(branches.contains(n.getBranchId()), "Unknown branch for node: " + n.getName());
            require(n.getMinimumLevel() >= 2 && n.getMinimumLevel() <= PlayerCharacter.MAX_LEVEL,
                "Node minimum level must be between 2 and 30.");
            var r = n.getReward();
            require(r.getHitPoints() >= 0 && r.getHitPoints() <= 200 && r.getMana() >= 0 && r.getMana() <= 200,
                "HP and mana rewards must be between 0 and 200.");
            for (int bonus : new int[]{r.getArmorClass(), r.getAttackBonus(), r.getBasicAttackDamage(),
                    r.getRageDamage(), r.getAbilityIncrease()}) {
                require(bonus >= 0 && bonus <= 10, "Upgrade bonuses must be between 0 and 10.");
            }
            require(r.getSpeed() >= 0 && r.getSpeed() <= 60, "Speed reward must be between 0 and 60.");
            require(r.getAbilityIncrease() == 0 || text(r.getAbilityId()), "Choose an ability for its score increase.");
            require(r.getSpellIds().stream().allMatch(UpgradeRules::text)
                && new HashSet<>(r.getSpellIds()).size() == r.getSpellIds().size(), "Spell rewards need unique IDs.");
        }
        for (var n : nodes.values()) {
            for (var edges : List.of(n.getPrerequisites(), n.getExclusiveWith())) {
                require(new HashSet<>(edges).size() == edges.size(), "Duplicate links on node: " + n.getName());
                for (String target : edges) {
                    require(nodes.containsKey(target) && !n.getId().equals(target),
                        "Node links must reference another existing node: " + n.getName());
                }
            }
        }
        Map<String, Set<String>> closures = new HashMap<>();
        for (var n : nodes.values()) ancestors(n.getId(), nodes, closures, new HashSet<>());
        for (var n : nodes.values()) {
            Set<String> required = new HashSet<>(closures.get(n.getId()));
            required.add(n.getId());
            require(required.size() <= PlayerCharacter.MAX_LEVEL - 1, "This path needs more unlocks than a character can earn.");
            for (String a : required) {
                for (String b : nodes.get(a).getExclusiveWith()) {
                    require(!required.contains(b), "A node requires mutually exclusive choices: " + n.getName());
                }
            }
        }
    }

    private static Set<String> ancestors(String id, Map<String, UpgradeTree.Node> nodes,
                                          Map<String, Set<String>> done, Set<String> path) {
        if (done.containsKey(id)) return done.get(id);
        require(path.add(id), "Upgrade prerequisites contain a cycle.");
        Set<String> result = new LinkedHashSet<>();
        for (String parent : nodes.get(id).getPrerequisites()) {
            result.add(parent);
            result.addAll(ancestors(parent, nodes, done, path));
        }
        path.remove(id);
        done.put(id, result);
        return result;
    }

    /** Old levels are consumed by existing training, never awarded again or applied twice. */
    public static boolean initialize(PlayerCharacter pc, CharacterClass cls) {
        if (pc.getUpgrades() != null) return false;
        UpgradeProgress progress = new UpgradeProgress();
        int spent = Math.max(0, pc.getLevel() - 1);
        progress.setEarned(spent);
        List<ClassFeature> features = cls == null ? List.of() : cls.featuresUpTo(pc.getLevel(), pc.getSubclass());
        Set<String> names = features.stream().map(f -> normal(f.getName())).collect(Collectors.toSet());
        Set<String> spells = pc.getSpells() == null ? Set.of() : pc.getSpells().stream()
            .map(PlayerCharacter.PlayerSpell::getSpellId).collect(Collectors.toSet());
        if (cls != null) {
            validate(cls.getUpgradeTree());
            boolean changed;
            do {
                changed = false;
                for (var node : cls.getUpgradeTree().getNodes()) {
                    if (progress.getUnlocks().size() >= spent || node.getMinimumLevel() > pc.getLevel()
                            || selected(progress, cls.getId(), node.getId())) continue;
                    boolean known = names.contains(normal(node.getName()))
                        || (!node.getReward().getSpellIds().isEmpty() && spells.containsAll(node.getReward().getSpellIds()));
                    if (!known || !linksSatisfied(progress, cls, node)) continue;
                    var unlock = snapshot(cls, node, pc.getLevel());
                    unlock.setHistorical(true);
                    unlock.setReward(new UpgradeTree.Reward());
                    progress.getUnlocks().add(unlock);
                    changed = true;
                }
            } while (changed && progress.getUnlocks().size() < spent);
        }
        for (int level = progress.getUnlocks().size() + 2; progress.getUnlocks().size() < spent; level++) {
            var unlock = new UpgradeProgress.Unlock();
            unlock.setClassId(pc.getClassId());
            unlock.setNodeId("@historical-level-" + level);
            unlock.setLevel(level);
            unlock.setHistorical(true);
            unlock.setBranchName("Existing training");
            final int atLevel = level;
            String learned = features.stream().filter(f -> f.getLevel() == atLevel)
                .map(ClassFeature::getName).collect(Collectors.joining(", "));
            unlock.setName(learned.isBlank() ? "Existing level " + level + " training" : learned);
            unlock.setDescription("Already included in this character. No rewards are applied again."
                + (learned.isBlank() ? "\n" + features.stream().map(ClassFeature::getName).collect(Collectors.joining(", ")) : "")
                + (spells.isEmpty() ? "" : "\nKnown abilities: " + String.join(", ", new TreeSet<>(spells))));
            progress.getUnlocks().add(unlock);
        }
        pc.setUpgrades(progress);
        return true;
    }

    public static int available(PlayerCharacter pc) {
        var p = pc.getUpgrades();
        return p == null ? 0 : Math.max(0, p.getEarned() - p.getUnlocks().size());
    }

    public static boolean selected(UpgradeProgress progress, String classId, String nodeId) {
        return progress != null && progress.getUnlocks().stream().anyMatch(u ->
            Objects.equals(classId, u.getClassId()) && Objects.equals(nodeId, u.getNodeId()));
    }

    private static boolean linksSatisfied(UpgradeProgress p, CharacterClass cls, UpgradeTree.Node n) {
        if (!n.getPrerequisites().stream().allMatch(id -> selected(p, cls.getId(), id))) return false;
        for (var u : p.getUnlocks()) {
            if (!Objects.equals(u.getClassId(), cls.getId())) continue;
            if (n.getExclusiveWith().contains(u.getNodeId()) || u.getExclusiveWith().contains(n.getId())) return false;
            var live = cls.getUpgradeTree().getNodes().stream().filter(x -> x.getId().equals(u.getNodeId())).findFirst();
            if (live.isPresent() && live.get().getExclusiveWith().contains(n.getId())) return false;
        }
        return true;
    }

    public static String blockedReason(PlayerCharacter pc, CharacterClass cls, UpgradeTree.Node n) {
        if (selected(pc.getUpgrades(), cls.getId(), n.getId())) return "Already unlocked";
        if (pc.getLevel() < n.getMinimumLevel()) return "Required level: " + n.getMinimumLevel();
        if (pc.getUpgrades() == null || !linksSatisfied(pc.getUpgrades(), cls, n)) return "Prerequisites or exclusive choices block this node.";
        if (available(pc) == 0) return "No unlocks available. Gain a level to earn one.";
        return "";
    }

    public static void unlock(PlayerCharacter pc, CharacterClass cls, String nodeId, Function<String, Spell> spells) {
        require(pc != null && cls != null && text(cls.getId()) && Objects.equals(pc.getClassId(), cls.getId()),
            "This character or class changed. Reopen the upgrade tree before saving.");
        validate(cls.getUpgradeTree());
        initialize(pc, cls);
        var node = cls.getUpgradeTree().getNodes().stream().filter(n -> n.getId().equals(nodeId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown upgrade node: " + nodeId));
        String blocked = blockedReason(pc, cls, node);
        require(blocked.isBlank(), blocked);
        var reward = node.getReward();
        List<PlayerCharacter.PlayerSpell> grants = new ArrayList<>();
        for (String id : reward.getSpellIds()) {
            Spell spell = spells.apply(id);
            require(spell != null, "Unknown spell or ability: " + id);
            boolean known = pc.getSpells() != null && pc.getSpells().stream().anyMatch(s -> id.equals(s.getSpellId()));
            if (!known) {
                var grant = new PlayerCharacter.PlayerSpell();
                grant.setSpellId(id);
                grant.setRank(spell.getLevel());
                grant.setActive(true);
                grants.add(grant);
            }
        }
        int newScore = 0;
        if (reward.getAbilityIncrease() > 0) {
            require(pc.getStats() != null && pc.getStats().schema().getAbilities().stream()
                .anyMatch(a -> a.id().equals(reward.getAbilityId())), "Unknown ability score: " + reward.getAbilityId());
            newScore = pc.getStats().score(reward.getAbilityId()) + reward.getAbilityIncrease();
            require(newScore <= 30, "This reward would raise an ability score above 30.");
        }
        var saved = snapshot(cls, node, pc.getLevel());
        int maxHp = Math.addExact(pc.getMaxHitPoints(), reward.getHitPoints());
        int currentHp = Math.addExact(pc.getCurrentHitPoints(), reward.getHitPoints());
        int maxMana = Math.addExact(pc.getMaxMana(), reward.getMana());
        int currentMana = Math.addExact(pc.getCurrentMana(), reward.getMana());
        if (reward.getAbilityIncrease() > 0) pc.getStats().score(reward.getAbilityId(), newScore);
        pc.setMaxHitPoints(maxHp);
        pc.setCurrentHitPoints(currentHp);
        pc.setMaxMana(maxMana);
        pc.setCurrentMana(currentMana);
        List<PlayerCharacter.PlayerSpell> known = new ArrayList<>(pc.getSpells() == null ? List.of() : pc.getSpells());
        known.addAll(grants);
        pc.setSpells(known);
        pc.getUpgrades().getUnlocks().add(saved);
    }

    private static UpgradeProgress.Unlock snapshot(CharacterClass cls, UpgradeTree.Node n, int level) {
        var u = new UpgradeProgress.Unlock();
        u.setClassId(cls.getId());
        u.setNodeId(n.getId());
        u.setName(n.getName());
        u.setDescription(n.getDescription());
        u.setImagePath(n.getImagePath());
        u.setLevel(level);
        u.setBranchName(cls.getUpgradeTree().getBranches().stream().filter(b -> b.getId().equals(n.getBranchId()))
            .findFirst().orElseThrow().getName());
        u.setExclusiveWith(new ArrayList<>(n.getExclusiveWith()));
        u.setReward(copy(n.getReward()));
        return u;
    }

    private static UpgradeTree.Reward copy(UpgradeTree.Reward r) {
        var c = new UpgradeTree.Reward();
        c.setHitPoints(r.getHitPoints()); c.setMana(r.getMana());
        c.setAbilityId(r.getAbilityId()); c.setAbilityIncrease(r.getAbilityIncrease());
        c.setArmorClass(r.getArmorClass()); c.setAttackBonus(r.getAttackBonus());
        c.setBasicAttackDamage(r.getBasicAttackDamage()); c.setRageDamage(r.getRageDamage());
        c.setSpeed(r.getSpeed()); c.setSpellIds(new ArrayList<>(r.getSpellIds()));
        return c;
    }

    public static int bonus(PlayerCharacter pc, java.util.function.ToIntFunction<UpgradeTree.Reward> value) {
        return pc == null || pc.getUpgrades() == null ? 0 : pc.getUpgrades().getUnlocks().stream()
            .mapToInt(u -> value.applyAsInt(u.getReward())).sum();
    }

    private static String normal(String s) { return s == null ? "" : s.strip().toLowerCase(Locale.ROOT); }
    private static boolean text(String s) { return s != null && !s.isBlank(); }
    private static boolean id(String s) { return s != null && s.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*"); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
