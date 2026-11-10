package com.dnd.lan;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.CharacterRace;
import com.dnd.model.character.ClassFeature;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.character.SheetDetails;
import com.dnd.model.character.stats.Skills;
import com.dnd.model.item.Item;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.EnergyCharges;
import com.dnd.model.magic.Spell;
import com.dnd.model.magic.SpellSlot;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.rules.FeatureRules;
import com.dnd.model.rules.SheetValues;
import com.dnd.model.world.map.ActiveEffect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The character sheet a player sees in their browser during a local-network session, as JSON.
 *
 * <p>Every number comes from {@link SheetValues} and {@link FeatureRules}, the same code the
 * printed sheet uses, so the browser, the PDF and the GM's character panel always agree.
 * Only what is on the character's own sheet is included: nothing about other characters,
 * NPCs or the world.</p>
 */
public final class LanSheet {

    private LanSheet() {
    }

    /**
     * Builds the sheet of {@code pc}. {@code tr} translates the few labels that are made here
     * rather than in the page (ability and skill names, slot rows); pass {@code s -> s} for English.
     */
    public static ObjectNode build(PlayerCharacter pc, CampaignRepositories repos, String campaign,
                                   Function<String, String> tr, ObjectMapper mapper) {
        if (pc.getStats() == null) pc.setStats(new com.dnd.model.character.stats.CoreStats(10, 10, 10, 10, 10, 10));
        if (repos != null) {
            pc.getStats().configure(repos.statSchema());
            pc.getSkills().configure(repos.statSchema());
        }
        CharacterClass cls = repos == null ? null : safe(() -> repos.classes().getById(pc.getClassId()));
        CharacterRace race = repos == null ? null : safe(() -> repos.races().getById(pc.getRaceId()));
        Map<String, Item> items = new HashMap<>();
        Map<String, Spell> spells = new HashMap<>();
        if (repos != null) {
            List<Item> itemList = safe(() -> repos.items().list());
            if (itemList != null) for (Item i : itemList) if (i != null && i.getId() != null) items.put(i.getId(), i);
            List<Spell> spellList = safe(() -> repos.spells().list());
            if (spellList != null) for (Spell s : spellList) if (s != null && s.getId() != null) spells.put(s.getId(), s);
        }
        Function<String, Item> itemOf = id -> id == null ? null : items.get(id);
        FeatureRules.Kit kitOrNull = safe(() -> FeatureRules.kit(pc, cls, race));
        FeatureRules.Kit kit = kitOrNull == null ? new FeatureRules.Kit() : kitOrNull;
        SheetDetails sheet = pc.getSheet();

        ObjectNode root = mapper.createObjectNode();
        root.put("id", pc.getId());
        root.put("campaign", campaign);
        root.put("name", nz(pc.getName()));
        root.put("playerName", nz(pc.getPlayerName()));
        root.put("className", cls != null && !blank(cls.getName()) ? cls.getName() : nz(pc.getClassId()));
        root.put("subclass", nz(pc.getSubclass()));
        root.put("race", race != null && !blank(race.getName()) ? race.getName() : nz(pc.getRaceId()));
        root.put("level", Math.max(1, pc.getLevel()));
        root.put("xp", pc.getXp());
        root.put("portrait", !blank(pc.getImagePath()));

        ObjectNode hp = root.putObject("hp");
        hp.put("current", pc.getCurrentHitPoints());
        hp.put("max", pc.getMaxHitPoints());
        hp.put("temp", sheet.getTemporaryHitPoints());
        root.put("deathSuccesses", sheet.getDeathSaveSuccesses());
        root.put("deathFailures", sheet.getDeathSaveFailures());
        root.put("inspiration", sheet.isInspiration());

        FeatureRules.ArmorClass ac = safe(() -> SheetValues.armorClass(pc, kit, itemOf));
        root.put("armorClass", ac == null ? 10 + SheetValues.mod(pc, "dex") : ac.total());
        root.put("armorBreakdown", ac == null ? "" : nz(ac.breakdown()));
        root.put("initiative", SheetValues.signed(SheetValues.initiative(pc)));
        Integer speed = safe(() -> SheetValues.speed(pc, race, kit, itemOf));
        root.put("speed", speed == null ? SheetValues.DEFAULT_SPEED : speed);
        root.put("proficiency", SheetValues.signed(SheetValues.proficiencyBonus(pc)));
        root.put("passivePerception", SheetValues.passivePerception(pc));
        root.put("hitDice", nz(SheetValues.hitDice(pc, cls)));

        ArrayNode abilities = root.putArray("abilities");
        for (var def : pc.getStats().schema().getAbilities()) {
            String key = def.id();
            ObjectNode a = abilities.addObject();
            a.put("key", def.abbreviation());
            a.put("name", tr.apply(def.name()));
            a.put("score", pc.getStats().score(key));
            a.put("mod", SheetValues.signed(SheetValues.mod(pc, key)));
            a.put("save", SheetValues.signed(SheetValues.savingThrow(pc, cls, key)));
            a.put("saveProficient", SheetValues.saveProficient(pc, cls, key));
        }

        ArrayNode skills = root.putArray("skills");
        for (var def : pc.getSkills().schema().getSkills()) {
            ObjectNode s = skills.addObject();
            s.put("name", tr.apply(def.name()));
            s.put("ability", pc.getSkills().schema().ability(def.ability()).abbreviation());
            s.put("bonus", SheetValues.signed(pc.getSkills().get(def.id())));
        }

        String castAbility = SheetValues.spellcastingAbility(pc, cls);
        if (castAbility != null) {
            ObjectNode casting = root.putObject("casting");
            casting.put("ability", pc.getStats().schema().ability(SheetValues.abilityId(pc, castAbility)).abbreviation());
            casting.put("saveDc", SheetValues.spellSaveDc(pc, cls));
            casting.put("attack", SheetValues.signed(SheetValues.spellAttackBonus(pc, cls)));
        }
        if (pc.getMaxMana() > 0) {
            ObjectNode mana = root.putObject("mana");
            mana.put("current", pc.getCurrentMana());
            mana.put("max", pc.getMaxMana());
        }
        CastingResource resourceOrNull = safe(() -> Progression.resourceOf(pc, cls));
        CastingResource resource = resourceOrNull == null ? CastingResource.NONE : resourceOrNull;
        ArrayNode slots = root.putArray("slots");
        if (resource.usesSlots() && pc.getSpellSlots() != null) {
            List<SpellSlot> sorted = new ArrayList<>();
            for (SpellSlot s : pc.getSpellSlots()) if (s != null && s.getMax() > 0) sorted.add(s);
            sorted.sort(Comparator.comparingInt(SpellSlot::getLevel));
            for (SpellSlot s : sorted) {
                ObjectNode n = slots.addObject();
                n.put("label", tr.apply(resource == CastingResource.ENERGY_CHARGES
                    ? EnergyCharges.name(s.getLevel()) : SpellSlots.ordinal(s.getLevel()) + " level"));
                n.put("current", s.getCurrent());
                n.put("max", s.getMax());
            }
        }

        ArrayNode effects = root.putArray("effects");
        if (pc.getActiveEffects() != null) {
            for (ActiveEffect e : pc.getActiveEffects()) {
                if (e == null || e.isExpired()) continue;
                ObjectNode n = effects.addObject();
                n.put("name", nz(e.getName()));
                n.put("description", nz(e.getDescription()));
                n.put("rounds", e.getRemainingRounds());
            }
        }

        ArrayNode attacks = root.putArray("attacks");
        List<FeatureRules.Attack> weaponAttacks = safe(() -> FeatureRules.attacks(pc, kit, itemOf));
        if (weaponAttacks != null) {
            for (FeatureRules.Attack a : weaponAttacks) {
                attack(attacks, a.name(), SheetValues.signed(a.toHit()), a.damageLabel());
            }
        }
        for (SheetDetails.Attack a : sheet.getExtraAttacks()) {
            if (a != null && !blank(a.getName())) {
                attack(attacks, a.getName().trim(), nz(a.getAttackBonus()).trim(), nz(a.getDamage()).trim());
            }
        }

        ArrayNode actions = root.putArray("actions");
        for (FeatureRules.Action a : kit.getActions()) {
            ObjectNode n = actions.addObject();
            n.put("name", nz(a.getName()));
            n.put("description", nz(a.getDescription()));
            n.put("uses", a.usesLabel(pc.getFeatureUses()));
        }
        ArrayNode passives = root.putArray("passives");
        for (FeatureRules.Passive p : kit.getPassives()) {
            ObjectNode n = passives.addObject();
            n.put("name", nz(p.name()));
            n.put("description", nz(p.description()));
        }
        ArrayNode features = root.putArray("classFeatures");
        List<ClassFeature> featureList = safe(() -> FeatureRules.features(pc, cls));
        if (featureList != null) {
            for (ClassFeature f : featureList) {
                if (f == null || blank(f.getName())) continue;
                ObjectNode n = features.addObject();
                n.put("name", f.getName());
                n.put("description", nz(f.getDescription()));
                n.put("level", f.getLevel());
            }
        }

        ArrayNode spellList = root.putArray("spells");
        if (pc.getSpells() != null) {
            List<ObjectNode> rows = new ArrayList<>();
            for (PlayerCharacter.PlayerSpell ps : pc.getSpells()) {
                if (ps == null) continue;
                Spell s = spells.get(ps.getSpellId());
                ObjectNode n = mapper.createObjectNode();
                n.put("name", s != null && !blank(s.getName()) ? s.getName() : nz(ps.getSpellId()));
                n.put("level", s == null ? 0 : s.getLevel());
                n.put("description", s == null ? "" : nz(s.getDescription()));
                n.put("manaCost", s == null ? 0 : s.getManaCost());
                rows.add(n);
            }
            rows.sort(Comparator.comparingInt((ObjectNode n) -> n.get("level").asInt())
                .thenComparing(n -> n.get("name").asText()));
            rows.forEach(spellList::add);
        }

        ArrayNode inventory = root.putArray("items");
        if (pc.getItems() != null) {
            for (PlayerCharacter.PlayerItem pi : pc.getItems()) {
                if (pi == null) continue;
                Item item = items.get(pi.getItemId());
                ObjectNode n = inventory.addObject();
                n.put("name", item != null && !blank(item.getName()) ? item.getName() : nz(pi.getItemId()));
                n.put("quantity", Math.max(1, pi.getQuantity()));
                n.put("equipped", pi.isEquipped());
                n.put("description", item == null ? "" : nz(item.getDescription()));
            }
        }

        root.put("proficiencies", nz(sheet.getProficienciesAndLanguages()));
        root.put("features", nz(sheet.getExtraFeatures()));
        root.put("notes", nz(sheet.getNotes()));
        return root;
    }

    private static void attack(ArrayNode out, String name, String toHit, String damage) {
        ObjectNode n = out.addObject();
        n.put("name", nz(name));
        n.put("toHit", nz(toHit));
        n.put("damage", nz(damage));
    }

    private static int score(PlayerCharacter pc, int i) {
        var s = pc.getStats();
        if (s == null) return 10;
        return switch (i) {
            case 0 -> s.getStrength();
            case 1 -> s.getDexterity();
            case 2 -> s.getConstitution();
            case 3 -> s.getIntelligence();
            case 4 -> s.getWisdom();
            default -> s.getCharisma();
        };
    }

    /** A broken catalog entry should cost one line of the sheet, not the whole page. */
    private static <T> T safe(Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
