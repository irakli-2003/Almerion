package com.dnd.model.character.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Campaign-owned definitions and explicit bindings for the rules engine's stat roles. */
public class StatSchema {
    public static class Ability {
        private String id;
        private String name;
        private String abbreviation;
        public Ability() { }
        public Ability(String id, String name, String abbreviation) {
            this.id = id; this.name = name; this.abbreviation = abbreviation;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String abbreviation() { return abbreviation; }
        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getAbbreviation() { return abbreviation; }
        public void setAbbreviation(String value) { abbreviation = value; }
    }
    public static class Skill {
        private String id;
        private String name;
        private String ability;
        public Skill() { }
        public Skill(String id, String name, String ability) { this.id = id; this.name = name; this.ability = ability; }
        public String id() { return id; }
        public String name() { return name; }
        public String ability() { return ability; }
        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getAbility() { return ability; }
        public void setAbility(String value) { ability = value; }
    }
    public static final List<String> ROLES = List.of("strength", "dexterity", "constitution",
        "intelligence", "wisdom", "charisma");

    private List<Ability> abilities = new ArrayList<>();
    private List<Skill> skills = new ArrayList<>();
    private Map<String, String> abilityBindings = new LinkedHashMap<>();
    private Map<String, String> skillBindings = new LinkedHashMap<>();

    public StatSchema() {
        String[] names = {"Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma"};
        for (int i = 0; i < ROLES.size(); i++) {
            String id = ROLES.get(i);
            abilities.add(new Ability(id, names[i], id.substring(0, 3).toUpperCase(java.util.Locale.ROOT)));
            abilityBindings.put(id, id);
        }
        for (Skills.Def def : Skills.ALL) {
            skills.add(new Skill(def.key(), def.label(), def.ability()));
        }
        skillBindings.put("perception", "perception");
    }

    public List<Ability> getAbilities() { return abilities; }
    public void setAbilities(List<Ability> value) { abilities = new ArrayList<>(value); }
    public List<Skill> getSkills() { return skills; }
    public void setSkills(List<Skill> value) { skills = new ArrayList<>(value); }
    public Map<String, String> getAbilityBindings() { return abilityBindings; }
    public void setAbilityBindings(Map<String, String> value) { abilityBindings = new LinkedHashMap<>(value); }
    public Map<String, String> getSkillBindings() { return skillBindings; }
    public void setSkillBindings(Map<String, String> value) { skillBindings = new LinkedHashMap<>(value); }

    public String abilityFor(String role) {
        String id = abilityBindings.get(role);
        if (id == null) throw new IllegalArgumentException("Unmapped ability rule: " + role);
        return id;
    }
    public String skillFor(String role) {
        String id = skillBindings.get(role);
        if (id == null) throw new IllegalArgumentException("Unmapped skill rule: " + role);
        return id;
    }
    public Ability ability(String id) {
        return abilities.stream().filter(a -> a.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown ability: " + id));
    }
    public String resolveAbility(String key) {
        if (key == null) throw new IllegalArgumentException("An ability reference is required.");
        for (String role : ROLES) {
            if (role.equalsIgnoreCase(key) || role.substring(0, 3).equalsIgnoreCase(key)) return abilityFor(role);
        }
        String mapped = abilityBindings.getOrDefault(key, key);
        return ability(mapped).id();
    }
    public void validate() {
        Set<String> ids = new java.util.HashSet<>();
        Set<String> foldedIds = new java.util.HashSet<>();
        Set<String> abbreviations = new java.util.HashSet<>();
        for (Ability a : abilities) {
            if (a.id() == null || !a.id().matches("[a-zA-Z][a-zA-Z0-9_-]*") || !ids.add(a.id())
                    || !foldedIds.add(a.id().toLowerCase(java.util.Locale.ROOT))
                    || ROLES.stream().anyMatch(role -> role.substring(0, 3).equalsIgnoreCase(a.id()))
                    || a.name() == null || a.name().isBlank() || a.abbreviation() == null || a.abbreviation().isBlank()
                    || !abbreviations.add(a.abbreviation().toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Abilities need unique IDs, names and abbreviations.");
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one ability is required.");
        for (String role : ROLES) if (!ids.contains(abilityBindings.get(role))) {
            throw new IllegalArgumentException("Remap the ability rule before removing its stat: " + role);
        }
        for (var entry : abilityBindings.entrySet()) if (!ids.contains(entry.getValue())) {
            throw new IllegalArgumentException("Remap the ability reference: " + entry.getKey());
        }
        Set<String> skillIds = new java.util.HashSet<>();
        for (Skill s : skills) {
            if (s.id() == null || !s.id().matches("[a-zA-Z][a-zA-Z0-9_-]*") || !skillIds.add(s.id())
                    || s.name() == null || s.name().isBlank() || !ids.contains(s.ability())) {
                throw new IllegalArgumentException("Skills need unique IDs, names and a valid parent ability.");
            }
        }
        // Only Perception has a numerical rules dependency; other skills may be removed.
        if (!skillIds.contains(skillBindings.get("perception"))) {
            throw new IllegalArgumentException("Remap passive perception before removing its skill.");
        }
        for (var entry : skillBindings.entrySet()) {
            if (!skillIds.contains(entry.getValue())) {
                throw new IllegalArgumentException("Remap or remove the skill binding: " + entry.getKey());
            }
        }
    }
}
