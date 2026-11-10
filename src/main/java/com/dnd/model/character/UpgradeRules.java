package com.dnd.model.character;

import com.dnd.model.magic.Spell;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class UpgradeRules {
    public static final String ROOT = "class-foundation";
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
        List<ClassFeature> earlyTraining = cls.getFeatures().stream().filter(f -> f != null && text(f.getName())
            && !f.getName().equalsIgnoreCase("Ability Score Improvement")
            && f.getLevel() > 1 && f.getLevel() <= 5 && (f.getSubclass() == null || f.getSubclass().isBlank()))
            .sorted(Comparator.comparingInt(ClassFeature::getLevel)).toList();
        for (int branch = 0; branch < 2; branch++) {
            var b = new UpgradeTree.Branch();
            b.setId(branch == 0 ? "potency" : "resilience"); b.setName(names[branch]);
            tree.getBranches().add(b);
            for (int index = 1; index <= 7; index++) {
                int tier = index == 1 ? 0 : index <= 3 ? 1 : 2;
                var n = new UpgradeTree.Node();
                n.setId(b.getId() + "-" + index); n.setBranchId(b.getId());
                String[] titles = branch == 0
                    ? new String[]{"Opening", "Precision", "Momentum", "Masterstroke", "Breakthrough", "Counterplay", "Crescendo"}
                    : new String[]{"Vigil", "Shelter", "Footwork", "Sanctuary", "Last Stand", "Safe Passage", "Rally"};
                n.setName(names[branch] + ": " + titles[index - 1]);
                n.setMinimumLevel(new int[]{2, 4, 8}[tier]);
                int trainingIndex = (index - 1) * 2 + branch;
                if (index <= 2 && trainingIndex < earlyTraining.size()) {
                    n.getExistingTraining().add(earlyTraining.get(trainingIndex).getName());
                }
                n.getPrerequisites().add(index == 1 ? ROOT : b.getId() + "-" + (index / 2));
                if (index >= 4) n.getPrerequisites().add((branch == 0 ? "resilience" : "potency")
                    + "-" + (index <= 5 ? 2 : 3));
                String[] feats = branch == 0
                    ? new String[]{
                        "Once per short rest, after observing an enemy for a full turn, ask the GM which of its visible defenses is weakest. This reveals no hidden statistics.",
                        caster ? "Once per short rest, create a harmless palm-sized magical signal visible to an ally within 60 ft for one minute. It cannot blind, damage or imitate a spell."
                            : "Once per short rest, replace one attack with a feint: contest Athletics or Acrobatics against a nearby enemy's Insight. On success, the next ally's attack against it before your next turn has advantage.",
                        "Once per short rest, after helping an ally succeed on a check, let that ally move 5 ft to clear supported ground without provoking from the creature you distracted. Uses your reaction.",
                        "Once per long rest, after missing an attack, reveal one observed weakness to an ally who can hear you. Their next attack against that target before your next turn gains +2 to hit. No stacking.",
                        "Once per short rest, replace one attack with a contested Athletics check against a creature within reach, at most one size larger. On success push it 5 ft to clear supported ground, never off a ledge. No damage.",
                        caster ? "Once per short rest, use your reaction when a visible enemy begins casting within 60 ft to identify its visible target or area with an Arcana check set by the GM. This does not interrupt the spell."
                            : "Once per short rest, when an enemy misses you in melee, use your reaction to move 5 ft to clear supported ground without provoking from that enemy. No attack is granted.",
                        "Once per long rest, spend your action directing two allies who can hear you within 30 ft. Each may use their reaction to move 10 ft to clear ground without provoking. No attacks or extra actions."}
                    : new String[]{
                        "Once per short rest, spend your action watching a passage for one minute. The GM grants advantage on your next Perception check to notice a creature crossing it, not to detect hidden magic.",
                        "Once per short rest, use your reaction before a visible attack roll against an adjacent ally to grant +2 AC against that attack only. You must be conscious. Does not stack with other guardian training.",
                        "Once per short rest, spend a bonus action to ignore nonmagical difficult terrain for your next 10 ft of movement this turn. Does not add movement or permit unsafe ground.",
                        "Once per long rest, spend an action and touch a conscious ally. For one minute they have advantage on their next save against fear. Does not remove existing fear or grant immunity.",
                        "Once per long rest, use your reaction when you fail a save against being knocked prone to reroll it. You must keep the new result.",
                        "Once per short rest, spend an action helping an adjacent ally cross an obstacle. They have advantage on their next Athletics or Acrobatics check to cross it before your next turn. No extra movement.",
                        "Once per long rest, spend your action rallying a conscious ally within 30 ft who can hear you. They may immediately repeat one save against fear, using their reaction. No healing or extra actions."};
                n.setDescription(feats[index - 1] + " The GM resolves this feat; the app tracks its rest use.");
                var r = n.getReward();
                var feat = new ClassFeature(n.getMinimumLevel(), n.getName(), n.getDescription());
                feat.setUsesPerRest(1);
                feat.setShortRestRecharge(!feats[index - 1].startsWith("Once per long rest"));
                r.getFeatures().add(feat);
                if (!n.getExistingTraining().isEmpty()) {
                    var existing = earlyTraining.get(trainingIndex);
                    n.setName(existing.getName());
                    n.setMinimumLevel(Math.max(n.getMinimumLevel(), existing.getLevel()));
                    n.setDescription(Objects.toString(existing.getDescription(), "")
                        + "\nClass training. If already known, this feat is not granted a second time.");
                    var learned = existing.copy();
                    learned.setDescription(n.getDescription());
                    r.setFeatures(List.of(learned));
                }
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

    /** Upgrade only untouched 1.3.0 starter definitions, never GM-authored trees. */
    public static boolean isLegacyStarter(CharacterClass cls, UpgradeTree tree) {
        if (tree.getBranches().size() != 2 || tree.getNodes().size() != 8) return false;
        UpgradeTree current = starter(cls);
        for (int branch = 0; branch < 2; branch++) {
            var b = tree.getBranches().get(branch);
            var expected = current.getBranches().get(branch);
            if (!Objects.equals(b.getId(), expected.getId()) || !Objects.equals(b.getName(), expected.getName())) return false;
            for (int tier = 0; tier < 4; tier++) {
                String nodeId = b.getId() + "-" + (tier + 1);
                var match = tree.getNodes().stream().filter(n -> nodeId.equals(n.getId())).findFirst();
                if (match.isEmpty()) return false;
                var n = match.get();
                if (!Objects.equals(n.getBranchId(), b.getId()) || !Objects.equals(n.getName(), b.getName() + " " + (tier + 1))
                        || !Objects.equals(n.getDescription(), "A step along the " + b.getName() + " path. The GM can replace these starter rewards.")
                        || n.getImagePath() != null || !n.getExclusiveWith().isEmpty() || !n.getExistingTraining().isEmpty()
                        || n.getMinimumLevel() != new int[]{2, 6, 10, 14}[tier]
                        || !n.getPrerequisites().equals(tier == 0 ? List.of() : List.of(b.getId() + "-" + tier))) return false;
                var r = n.getReward();
                if (!r.getFeatures().isEmpty() || r.getMana() != 0 || r.getAbilityIncrease() != 0 || r.getAbilityId() != null) return false;
                boolean caster = cls.getSpellcasting() != null && cls.getSpellcasting() != com.dnd.model.magic.SpellcastingType.NONE;
                int hp = branch == 1 && tier == 0 && !"barbarian".equals(cls.getId()) ? 3 : branch == 1 && tier == 3 ? 5 : 0;
                int ac = branch == 1 && tier == 1 ? 1 : 0;
                int speed = tier == 2 ? 5 : 0;
                int attack = branch == 0 && tier == 1 ? 1 : 0;
                int rage = branch == 0 && tier == 0 && "barbarian".equals(cls.getId()) ? 1 : 0;
                var spells = branch == 0 && tier == 0 && rage == 0 && caster && !cls.getDefaultSpells().isEmpty()
                    ? List.of(cls.getDefaultSpells().getFirst()) : List.<String>of();
                int damage = branch == 1 && tier == 0 && "barbarian".equals(cls.getId()) ? 1
                    : branch == 0 && (tier == 3 || tier == 0 && rage == 0 && spells.isEmpty()) ? 1 : 0;
                if (r.getHitPoints() != hp || r.getArmorClass() != ac || r.getSpeed() != speed || r.getAttackBonus() != attack
                        || r.getRageDamage() != rage || r.getBasicAttackDamage() != damage || !r.getSpellIds().equals(spells)) return false;
            }
        }
        return true;
    }

    /** A virtual root represents class grants already applied by normal character rules. */
    public static UpgradeTree.Node foundation(CharacterClass cls, PlayerCharacter pc) {
        var root = new UpgradeTree.Node();
        root.setId(ROOT); root.setName(Objects.toString(cls.getName(), cls.getId()) + " Foundation");
        root.setMinimumLevel(1);
        String subclass = pc == null ? null : pc.getSubclass();
        String basics = cls.featuresUpTo(1, subclass).stream()
            .map(f -> f.getName() + ": " + Objects.toString(f.getDescription(), "")).collect(Collectors.joining("\n"));
        root.setDescription("Always unlocked. Starting class abilities and bonuses are already provided by the class; no rewards are applied twice."
            + "\n" + basics + "\nHit die: " + Objects.toString(cls.getHitDie(), "")
            + "\nSaving throws: " + Objects.toString(cls.getSavingThrowBonuses(), "")
            + "\nStarting spells / abilities: " + String.join(", ", cls.getDefaultSpells()));
        return root;
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
            require(!ROOT.equals(n.getId()), "The class foundation is built in and cannot be edited.");
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
            require(r.getFeatures().stream().allMatch(f -> f != null && text(f.getName()) && text(f.getDescription())
                    && f.getUsesPerRest() >= 0 && f.getUsesPerRest() <= 100),
                "Granted feats need a name and a description.");
        }
        for (var n : nodes.values()) {
            for (var edges : List.of(n.getPrerequisites(), n.getExclusiveWith())) {
                require(new HashSet<>(edges).size() == edges.size(), "Duplicate links on node: " + n.getName());
                for (String target : edges) {
                    if (edges == n.getPrerequisites() && ROOT.equals(target)) continue;
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

    /** Enforced on whole-tree GM saves, not intermediate edits or legacy reads. */
    public static void validateBranching(UpgradeTree tree) {
        validate(tree);
        if (tree.getNodes().isEmpty()) return;
        Map<String, Integer> children = new HashMap<>();
        Map<String, Integer> depths = new HashMap<>();
        depths.put(ROOT, 0);
        for (var n : tree.getNodes()) {
            var parents = n.getPrerequisites().isEmpty() ? List.of(ROOT) : n.getPrerequisites();
            for (String parent : parents) children.merge(parent, 1, Integer::sum);
        }
        require(children.values().stream().allMatch(count -> count >= 2),
            "Every non-leaf node, including the class foundation, needs at least two children.");
        require(tree.getNodes().stream().filter(n -> !children.containsKey(n.getId())).count() >= 8,
            "A class tree needs at least eight leaf nodes.");
        while (depths.size() <= tree.getNodes().size()) {
            for (var n : tree.getNodes()) {
                var parents = n.getPrerequisites().isEmpty() ? List.of(ROOT) : n.getPrerequisites();
                if (!depths.containsKey(n.getId()) && parents.stream().allMatch(depths::containsKey)) {
                    depths.put(n.getId(), 1 + parents.stream().mapToInt(depths::get).max().orElseThrow());
                }
            }
        }
        require(depths.values().stream().mapToInt(Integer::intValue).max().orElse(0) >= 3,
            "A class tree needs at least four tiers including its foundation.");
        require(tree.getNodes().stream().anyMatch(n -> n.getPrerequisites().size() > 1),
            "Include a stronger node that requires training from more than one prerequisite.");
    }

    private static Set<String> ancestors(String id, Map<String, UpgradeTree.Node> nodes,
                                          Map<String, Set<String>> done, Set<String> path) {
        if (done.containsKey(id)) return done.get(id);
        require(path.add(id), "Upgrade prerequisites contain a cycle.");
        Set<String> result = new LinkedHashSet<>();
        for (String parent : nodes.get(id).getPrerequisites()) {
            if (ROOT.equals(parent)) continue;
            result.add(parent);
            result.addAll(ancestors(parent, nodes, done, path));
        }
        path.remove(id);
        done.put(id, result);
        return result;
    }

    /** Old levels are consumed by existing training, never awarded again or applied twice. */
    public static boolean initialize(PlayerCharacter pc, CharacterClass cls) {
        if (pc.getUpgrades() != null) return associateTraining(pc, cls);
        UpgradeProgress progress = new UpgradeProgress();
        int spent = Math.max(0, pc.getLevel() - 1);
        progress.setEarned(spent);
        List<ClassFeature> features = new ArrayList<>(cls == null ? List.of() : cls.featuresUpTo(pc.getLevel(), pc.getSubclass()));
        pc.getPersonalFeatures().stream().filter(f -> f != null && f.getLevel() <= pc.getLevel()
            && f.appliesTo(pc.getSubclass())).forEach(features::add);
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
                        || node.getExistingTraining().stream().map(UpgradeRules::normal).anyMatch(names::contains)
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

    private static boolean associateTraining(PlayerCharacter pc, CharacterClass cls) {
        if (cls == null) return false;
        validate(cls.getUpgradeTree());
        var features = com.dnd.model.rules.FeatureRules.features(pc, cls);
        Set<String> names = features.stream().map(f -> normal(f.getName())).collect(Collectors.toSet());
        Set<String> spells = pc.getSpells() == null ? Set.of() : pc.getSpells().stream()
            .map(PlayerCharacter.PlayerSpell::getSpellId).collect(Collectors.toSet());
        boolean changed = false;
        var starterNodes = starter(cls).getNodes();
        for (var u : pc.getUpgrades().getUnlocks()) {
            if (u.isHistorical() || !Objects.equals(cls.getId(), u.getClassId())
                    || u.getDescription() == null || !u.getReward().getFeatures().isEmpty()) continue;
            var node = cls.getUpgradeTree().getNodes().stream().filter(n -> Objects.equals(n.getId(), u.getNodeId())).findFirst();
            if (node.isEmpty()) continue;
            String legacy = "A step along the " + u.getBranchName() + " path. The GM can replace these starter rewards.";
            if (!legacy.equals(u.getDescription())) continue;
            var original = starterNodes.stream().filter(n -> Objects.equals(n.getId(), u.getNodeId())).findFirst();
            if (original.isEmpty() || !Objects.equals(node.get().getName(), original.get().getName())
                    || !Objects.equals(node.get().getDescription(), original.get().getDescription())) continue;
            u.getReward().setFeatures(original.get().getReward().getFeatures().stream().map(ClassFeature::copy).toList());
            if (!u.getReward().getFeatures().isEmpty()) {
                u.setDescription(u.getDescription() + "\n" + node.get().getDescription());
                changed = true;
            }
        }
        for (var node : cls.getUpgradeTree().getNodes()) {
            if (node.getMinimumLevel() > pc.getLevel() || selected(pc.getUpgrades(), cls.getId(), node.getId())
                    || !linksSatisfied(pc.getUpgrades(), cls, node)) continue;
            boolean known = names.contains(normal(node.getName()))
                || node.getExistingTraining().stream().map(UpgradeRules::normal).anyMatch(names::contains)
                || !node.getReward().getSpellIds().isEmpty() && spells.containsAll(node.getReward().getSpellIds());
            if (!known) continue;
            var credit = pc.getUpgrades().getUnlocks().stream().filter(u -> u.isHistorical()
                && Objects.equals(cls.getId(), u.getClassId()) && u.getNodeId() != null
                && u.getNodeId().startsWith("@historical-level-")).findFirst();
            if (credit.isEmpty()) break;
            var u = credit.get();
            u.setNodeId(node.getId()); u.setName(node.getName());
            u.setBranchName(cls.getUpgradeTree().getBranches().stream().filter(b -> b.getId().equals(node.getBranchId()))
                .findFirst().orElseThrow().getName());
            u.setDescription("Already included in this character. No rewards are applied again.\n"
                + String.join(", ", node.getExistingTraining()) + "\n" + Objects.toString(u.getDescription(), ""));
            changed = true;
        }
        return changed;
    }

    public static int available(PlayerCharacter pc) {
        var p = pc.getUpgrades();
        return p == null ? 0 : Math.max(0, p.getEarned() - p.getUnlocks().size());
    }

    public static boolean selected(UpgradeProgress progress, String classId, String nodeId) {
        if (ROOT.equals(nodeId)) return true;
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
        if (ROOT.equals(n.getId())) return "Always unlocked class foundation";
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
        c.setFeatures(r.getFeatures().stream().map(ClassFeature::copy).toList());
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
