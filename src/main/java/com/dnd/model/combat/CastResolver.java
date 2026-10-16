package com.dnd.model.combat;

import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.item.Item;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.SpellSlots;
import com.dnd.model.magic.Spell;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.model.world.map.CombatState;
import com.dnd.model.world.map.MapObject;
import com.dnd.model.world.map.PlayerToken;
import com.dnd.model.world.map.TokenSupport;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Turns "cast this spell at that square" into actual changes on the board.
 *
 * <p>Spells and abilities are described by two unrelated model classes with different
 * shapes - a spell embeds its {@link Effect}s and costs mana, an ability refers to effects
 * by id and costs nothing - so this first flattens either one into a {@link Castable} and
 * then resolves that. The rest of the app therefore only has to know about one casting
 * path, and the resolution rules live in one place that can be exercised without a UI.</p>
 *
 * <p>Nothing here knows about the grid. The caller works out who is in range and who is
 * caught in the blast; this class decides whether the cast is legal, charges for it, and
 * applies the consequences.</p>
 */
public final class CastResolver {

    private CastResolver() {
    }

    /**
     * A spell or an ability reduced to the handful of facts casting actually needs.
     */
    public static final class Castable {
        private final String id;
        private final String name;
        private final String description;
        private final boolean spell;
        private final int manaCost;
        private final double rangeFeet;
        private final double radiusFeet;
        private final int cooldownRounds;
        private final List<Effect> effects;
        private final Damage damage;
        private final List<Item> consumables;
        private final int spellLevel;
        private final int hpCost;
        /** Exact charge/slot level to spend; 0 = the lowest available at or above the spell level. */
        private int chargeLevel;

        private Castable(String id, String name, String description, boolean spell, int manaCost,
                         double rangeFeet, double radiusFeet, int cooldownRounds,
                         List<Effect> effects, Damage damage, List<Item> consumables,
                         int spellLevel, int hpCost) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.spell = spell;
            this.manaCost = manaCost;
            this.rangeFeet = rangeFeet;
            this.radiusFeet = radiusFeet;
            this.cooldownRounds = cooldownRounds;
            this.effects = effects == null ? List.of() : effects;
            this.damage = damage;
            this.consumables = consumables == null ? List.of() : consumables;
            this.spellLevel = Math.max(0, spellLevel);
            this.hpCost = Math.max(0, hpCost);
        }

        /** 0 for cantrips and abilities; otherwise the minimum spell slot level it needs. */
        public int getSpellLevel() {
            return spellLevel;
        }

        /** The exact charge level this cast spends, or 0 when any charge at or above the spell level will do. */
        public int getChargeLevel() {
            return chargeLevel;
        }

        /** Extra damage dice from running a mechanical device on a stronger charge than it needs. */
        public int getBoost() {
            return chargeLevel > spellLevel && spellLevel > 0 ? chargeLevel - spellLevel : 0;
        }

        /**
         * The same action locked to one charge level. Above the spell's own level it is boosted:
         * the first damage die is rolled once more per level of difference.
         */
        public Castable onCharge(int level) {
            int boost = level > spellLevel && spellLevel > 0 ? level - spellLevel : 0;
            Damage boosted = damage;
            if (boost > 0 && damage != null && damage.hasDice()) {
                List<DiceRoll> dice = new ArrayList<>();
                for (DiceRoll roll : damage.getDice()) {
                    dice.add(new DiceRoll(roll.getDiceId(), roll.getCount()));
                }
                dice.get(0).setCount(dice.get(0).getCount() + boost);
                boosted = new Damage(dice, damage.getTypeId());
            }
            Castable c = new Castable(id, name, description, spell, manaCost, rangeFeet, radiusFeet, cooldownRounds,
                effects, boosted, consumables, spellLevel, hpCost);
            c.chargeLevel = Math.max(0, level);
            return c;
        }

        /** Hit points always paid by the caster, whatever their casting resource. */
        public int getHpCost() {
            return hpCost;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name == null || name.isBlank() ? id : name;
        }

        public String getDescription() {
            return description;
        }

        public boolean isSpell() {
            return spell;
        }

        public int getManaCost() {
            return manaCost;
        }

        public double getRangeFeet() {
            return rangeFeet;
        }

        public double getRadiusFeet() {
            return radiusFeet;
        }

        public int getCooldownRounds() {
            return cooldownRounds;
        }

        public List<Effect> getEffects() {
            return effects;
        }

        public Damage getDamage() {
            return damage;
        }

        public List<Item> getConsumables() {
            return consumables;
        }

        /** True when this is aimed at a patch of ground and catches everyone standing in it. */
        public boolean isArea() {
            return radiusFeet > 0;
        }

        /** How this picks who it affects - see {@link Targeting}. */
        public Targeting getTargeting() {
            boolean hasDamage = damage != null && damage.hasDice();
            if (radiusFeet > 0) return rangeFeet > 0 ? Targeting.AREA : Targeting.AURA;
            if (rangeFeet <= 0 && !hasDamage) return Targeting.SELF;
            return Targeting.SINGLE;
        }

        /** False when it goes off around (or on) the caster the moment it is used. */
        public boolean needsTarget() {
            Targeting t = getTargeting();
            return t == Targeting.SINGLE || t == Targeting.AREA;
        }
    }

    /**
     * Who a spell or ability lands on. A range of zero means it starts at the caster: with a
     * radius it is an aura around them, without one (and without damage dice) it is used on
     * themselves. Range-zero damage with no radius is a touch and still needs a target.
     */
    public enum Targeting {
        /** Only the caster. */
        SELF,
        /** Every other creature within the radius of the caster. */
        AURA,
        /** Everyone within the radius of an aim point inside the range. */
        AREA,
        /** One chosen creature. */
        SINGLE
    }

    /** Flattens a spell into a castable. */
    public static Castable of(Spell spell) {
        if (spell == null) return null;
        return new Castable(spell.getId(), spell.getName(), spell.getDescription(), true,
            spell.getManaCost(), spell.getRange(), spell.getRadius(), spell.getCooldownRounds(),
            spell.getEffects(), spell.getDamage(), spell.getRequiredConsumables(), spell.getLevel(), 0);
    }

    /**
     * Flattens an ability into a castable, resolving its effect ids through the campaign's
     * effect catalog. Ids that no longer resolve are simply skipped so a deleted effect
     * cannot break an otherwise usable ability mid-fight.
     */
    public static Castable of(Ability ability, Function<String, Effect> effectLookup) {
        if (ability == null) return null;
        List<Effect> resolved = new ArrayList<>();
        if (ability.getEffects() != null && effectLookup != null) {
            for (String effectId : ability.getEffects()) {
                Effect effect = effectId == null ? null : effectLookup.apply(effectId);
                if (effect != null) resolved.add(effect);
            }
        }
        return new Castable(ability.getId(), ability.getName(), ability.getDescription(), false,
            ability.getManaCost(), ability.getRange(), ability.getRadius(), ability.getRecharge(),
            resolved, ability.getDamage(), List.of(), 0, ability.getHpCost());
    }

    /** What happened when a cast was attempted. */
    public static final class Outcome {
        private final boolean success;
        private final String message;
        private final List<String> log;

        private Outcome(boolean success, String message, List<String> log) {
            this.success = success;
            this.message = message;
            this.log = log == null ? List.of() : log;
        }

        static Outcome refused(String reason) {
            return new Outcome(false, reason, List.of());
        }

        public boolean isSuccess() {
            return success;
        }

        /** One-line summary suitable for a status bar. */
        public String getMessage() {
            return message;
        }

        /** Blow-by-blow detail of what each target suffered. */
        public List<String> getLog() {
            return log;
        }
    }

    /**
     * Checks whether the caster could use this right now, ignoring targets and range.
     *
     * @return null when it is castable, otherwise the reason it is not
     */
    public static String blockedReason(MapObject caster, Castable action) {
        if (caster == null || action == null) return "Nothing selected.";
        CombatState state = TokenSupport.combatOf(caster);
        if (state.isDead()) return TokenSupport.nameOf(caster) + " is dead.";
        int cooldown = state.cooldownFor(action.getId());
        if (cooldown > 0) {
            return action.getName() + " recharges in " + cooldown + (cooldown == 1 ? " round." : " rounds.");
        }
        String cost = costProblem(state, action);
        if (cost != null) return cost;
        String missing = missingConsumable(caster, action);
        if (missing != null) return "Missing " + missing + ".";
        return null;
    }

    /**
     * Resolves a cast against an already-chosen set of targets.
     *
     * @param caster        who is casting
     * @param action        the flattened spell or ability
     * @param targets       every creature caught by it; empty is allowed for a cast at empty ground
     * @param distanceFeet  how far the aim point is from the caster, for the range check
     * @param rolledDamage  the DM's own roll of {@code action.getDamage()}'s dice, entered by
     *                      hand rather than simulated; ignored when the action deals no damage
     */
    public static Outcome cast(MapObject caster, Castable action, List<MapObject> targets, double distanceFeet,
                               int rolledDamage) {
        String blocked = blockedReason(caster, action);
        if (blocked != null) return Outcome.refused(blocked);

        double range = action.getRangeFeet();
        // A range of zero means touch or self, which still has to be adjacent, not anywhere.
        double allowed = range > 0 ? range : 5;
        if (action.needsTarget() && distanceFeet > allowed + 0.001) {
            return Outcome.refused(action.getName() + " reaches " + (int) allowed
                + " ft but the target is " + (int) Math.round(distanceFeet) + " ft away.");
        }

        CombatState casterState = TokenSupport.combatOf(caster);
        List<String> log = new ArrayList<>();

        String paid = pay(casterState, action);
        if (paid != null) log.add(TokenSupport.nameOf(caster) + " " + paid);
        for (String consumed : consume(caster, action)) {
            log.add(TokenSupport.nameOf(caster) + " uses up " + consumed + ".");
        }

        List<MapObject> hit = new ArrayList<>();
        if (targets != null) {
            for (MapObject target : targets) {
                if (target != null && !hit.contains(target)) hit.add(target);
            }
        }
        Targeting targeting = action.getTargeting();
        if (targeting == Targeting.SELF) {
            hit.clear();
            hit.add(caster);
        } else if (targeting == Targeting.AURA) {
            hit.remove(caster);
        }
        for (MapObject target : hit) {
            log.addAll(applyTo(caster, target, action, rolledDamage));
        }
        if (!hasMechanicalEffect(action)) {
            // Nothing the board can apply by itself: leave the rules where the DM is looking
            // and mark the caster so it is clear on the map that this is in play.
            if (action.getDescription() != null && !action.getDescription().isBlank()) {
                log.add(action.getName() + ": " + action.getDescription());
            }
            if (targeting == Targeting.SELF) {
                ActiveEffect marker = new ActiveEffect(action.getId(), action.getName(), 1, 0, 0,
                    TokenSupport.nameOf(caster));
                marker.setDescription(action.getDescription());
                casterState.addEffect(marker);
            }
        }

        if (action.getCooldownRounds() > 0) {
            casterState.startCooldown(action.getId(), action.getCooldownRounds());
        }

        String summary = TokenSupport.nameOf(caster) + " " + (action.isSpell() ? "casts " : "uses ") + action.getName()
            + switch (targeting) {
                case SELF -> ".";
                case AURA -> hit.isEmpty() ? " - nobody is close enough." : " around itself, catching " + describeTargets(hit) + ".";
                default -> hit.isEmpty() ? " at empty ground." : " on " + describeTargets(hit) + ".";
            };
        return new Outcome(true, summary, log);
    }

    /** True when it deals damage or carries at least one effect the board can track. */
    private static boolean hasMechanicalEffect(Castable action) {
        if (action.getDamage() != null && action.getDamage().hasDice()) return true;
        for (Effect effect : action.getEffects()) {
            if (effect != null) return true;
        }
        return false;
    }

    // ── Costs ───────────────────────────────────────────────────────────────

    /**
     * Why the caster cannot afford this, or null when they can. Cantrips and abilities never
     * need slots; levelled spells use whatever the caster's casting resource is.
     */
    public static String costProblem(CombatState state, Castable action) {
        int hpNeeded = action.getHpCost();
        CastingResource resource = state.effectiveCastingResource();
        boolean levelled = action.isSpell() && action.getSpellLevel() > 0;
        if (resource == CastingResource.HIT_POINTS) hpNeeded += action.getManaCost();
        if (hpNeeded > 0 && hpNeeded >= state.getCurrentHitPoints()) {
            return action.getName() + " costs " + hpNeeded + " HP but only "
                + state.getCurrentHitPoints() + " HP remain.";
        }
        if (resource.usesSlots() && levelled && action.getChargeLevel() > 0) {
            com.dnd.model.magic.SpellSlot slot = SpellSlots.find(state.getSpellSlots(), action.getChargeLevel());
            if (slot == null || slot.getCurrent() <= 0) {
                return resource == CastingResource.ENERGY_CHARGES
                    ? "No " + com.dnd.model.magic.EnergyCharges.name(action.getChargeLevel()) + " charge left"
                        + (action.getChargeLevel() == action.getSpellLevel() ? " - " + action.getName()
                            + " only runs on that type." : ".")
                    : "No spell slot of level " + action.getChargeLevel() + " left.";
            }
        } else if (resource.usesSlots() && levelled) {
            if (SpellSlots.lowestAvailable(state.getSpellSlots(), action.getSpellLevel()) < 0) {
                return resource == CastingResource.ENERGY_CHARGES
                    ? "No " + com.dnd.model.magic.EnergyCharges.name(action.getSpellLevel())
                        + " charge (or stronger) left - refill at the workshop furnace."
                    : "No spell slot of level " + action.getSpellLevel() + " or higher left.";
            }
        } else if (resource == CastingResource.MANA && action.getManaCost() > state.getCurrentMana()) {
            return "Not enough mana - needs " + action.getManaCost()
                + ", has " + state.getCurrentMana() + ".";
        }
        return null;
    }

    /** Deducts the cost; returns a log fragment like "spends a 2nd-level slot." or null. */
    private static String pay(CombatState state, Castable action) {
        List<String> parts = new ArrayList<>();
        CastingResource resource = state.effectiveCastingResource();
        int hp = action.getHpCost();
        if (resource == CastingResource.HIT_POINTS) hp += action.getManaCost();
        if (resource.usesSlots() && action.isSpell() && action.getSpellLevel() > 0) {
            int slot = action.getChargeLevel() > 0 ? action.getChargeLevel()
                : SpellSlots.lowestAvailable(state.getSpellSlots(), action.getSpellLevel());
            if (slot > 0 && SpellSlots.spend(state.getSpellSlots(), slot)) {
                parts.add("spends " + SpellSlots.slotName(slot, resource)
                    + (action.getBoost() > 0 ? " (boosted: +" + action.getBoost() + " damage die)" : ""));
            }
        } else if (resource == CastingResource.MANA && action.getManaCost() > 0) {
            state.setCurrentMana(state.getCurrentMana() - action.getManaCost());
            parts.add("spends " + action.getManaCost() + " mana");
        }
        if (hp > 0) {
            state.setCurrentHitPoints(state.getCurrentHitPoints() - hp);
            parts.add("pays " + hp + " HP");
        }
        return parts.isEmpty() ? null : String.join(" and ", parts) + ".";
    }

    /** True when the DM needs to be asked for a rolled damage total before this can be cast. */
    public static boolean needsDamageRoll(Castable action) {
        return action != null && action.getDamage() != null && action.getDamage().hasDice();
    }

    /** Applies one castable's damage, healing and lasting effects to a single target. */
    private static List<String> applyTo(MapObject caster, MapObject target, Castable action, int rolledDamage) {
        List<String> log = new ArrayList<>();
        CombatState state = TokenSupport.combatOf(target);
        String targetName = TokenSupport.nameOf(target);
        String sourceName = TokenSupport.nameOf(caster) + "'s " + action.getName();

        int dealtTotal = 0;
        int directDamage = action.getDamage() != null && action.getDamage().hasDice() ? Math.max(0, rolledDamage) : 0;
        if (directDamage > 0) {
            com.dnd.model.rules.CombatRules.Hit landed =
                com.dnd.model.rules.CombatRules.damage(state, directDamage, action.getDamage().getTypeId());
            dealtTotal += landed.dealt();
            String type = action.getDamage().getTypeId();
            log.add(targetName + " takes " + landed.dealt()
                + (type == null || type.isBlank() ? "" : " " + type) + " damage" + landed.note() + ".");
        }

        for (Effect effect : action.getEffects()) {
            if (effect == null) continue;
            if (effect.isLasting()) {
                ActiveEffect active = new ActiveEffect(
                    effect.getId(),
                    effect.getName() == null || effect.getName().isBlank() ? action.getName() : effect.getName(),
                    effect.getDurationRounds(),
                    effect.isDamaging() ? effect.getDamageAmount() : 0,
                    effect.isHealing() ? effect.getHealingAmount() : 0,
                    sourceName);
                active.setDescription(effect.getDescription());
                state.addEffect(active);
                log.add(targetName + " is affected by " + active.getName()
                    + " for " + effect.getDurationRounds()
                    + (effect.getDurationRounds() == 1 ? " round." : " rounds."));
                continue;
            }
            // Instant effects land once, right now, and leave nothing behind.
            boolean instant = false;
            if (effect.isDamaging() && effect.getDamageAmount() > 0) {
                com.dnd.model.rules.CombatRules.Hit landed =
                    com.dnd.model.rules.CombatRules.damage(state, effect.getDamageAmount(), null);
                dealtTotal += landed.dealt();
                log.add(targetName + " takes " + landed.dealt() + " from " + displayName(effect, action)
                    + landed.note() + ".");
                instant = true;
            }
            if (effect.isHealing() && effect.getHealingAmount() > 0) {
                state.heal(effect.getHealingAmount());
                log.add(targetName + " recovers " + effect.getHealingAmount() + " from " + displayName(effect, action) + ".");
                instant = true;
            }
            if (!instant) {
                // A plain condition (prone, restrained, charmed...) with no timer of its own:
                // it stays on the token through the bearer's next turn, or until the DM clears it.
                int rounds = effect.getDurationRounds() > 0 ? effect.getDurationRounds()
                    : target == caster ? 1 : 2;
                ActiveEffect condition = new ActiveEffect(effect.getId(), displayName(effect, action), rounds, 0, 0,
                    sourceName);
                condition.setDescription(effect.getDescription());
                state.addEffect(condition);
                log.add(targetName + " is affected by " + condition.getName()
                    + " for " + rounds + (rounds == 1 ? " round." : " rounds."));
            }
        }

        if (dealtTotal > 0 && target != caster && drainsLife(action)) {
            CombatState casterState = TokenSupport.combatOf(caster);
            String casterName = TokenSupport.nameOf(caster);
            if (hasEffect(casterState, NO_HEALING_EFFECT)) {
                log.add(casterName + " cannot drink the life it tears out - healing is blocked.");
            } else {
                int before = casterState.getCurrentHitPoints();
                casterState.heal(dealtTotal);
                int gained = casterState.getCurrentHitPoints() - before;
                log.add(casterName + " drains " + gained + " HP from " + targetName + ".");
            }
        }

        if (state.getCurrentHitPoints() <= 0 && !state.isDead()) {
            log.add(targetName + " falls.");
        }
        return log;
    }

    /** Effect id that makes an attack heal its user for the damage it deals. */
    public static final String LIFE_DRAIN_EFFECT = "life_drain";
    /** Effect id that, while on a creature, stops it regaining HP from life drain. */
    public static final String NO_HEALING_EFFECT = "no_healing";

    private static boolean drainsLife(Castable action) {
        for (Effect effect : action.getEffects()) {
            if (effect != null && LIFE_DRAIN_EFFECT.equals(effect.getId())) return true;
        }
        return false;
    }

    private static boolean hasEffect(CombatState state, String effectId) {
        if (state.getActiveEffects() == null) return false;
        for (ActiveEffect active : state.getActiveEffects()) {
            if (active != null && effectId.equals(active.getEffectId())) return true;
        }
        return false;
    }

    private static String displayName(Effect effect, Castable action) {
        return effect.getName() == null || effect.getName().isBlank() ? action.getName() : effect.getName();
    }

    // ── Consumables ─────────────────────────────────────────────────────────

    /**
     * Name of the first required consumable the caster does not have, or null when the
     * component pouch is in order. Only player characters carry an inventory the app can
     * check, so anyone else is assumed to have what they need.
     */
    private static String missingConsumable(MapObject caster, Castable action) {
        if (action.getConsumables().isEmpty()) return null;
        PlayerCharacter character = characterOf(caster);
        if (character == null || character.getItems() == null) return null;
        List<String> carried = new ArrayList<>();
        for (PlayerCharacter.PlayerItem item : character.getItems()) {
            if (item == null || item.getItemId() == null) continue;
            for (int i = 0; i < item.getQuantity(); i++) carried.add(normalize(item.getItemId()));
        }
        for (Item required : action.getConsumables()) {
            if (required == null) continue;
            String key = requiredKey(required);
            if (key == null) continue;
            if (!carried.remove(key)) {
                return required.getName() == null ? required.getId() : required.getName();
            }
        }
        return null;
    }

    /**
     * Removes one of each required consumable from the caster's pack.
     *
     * @return the names of what was used up, for the log
     */
    private static List<String> consume(MapObject caster, Castable action) {
        List<String> used = new ArrayList<>();
        if (action.getConsumables().isEmpty()) return used;
        PlayerCharacter character = characterOf(caster);
        if (character == null || character.getItems() == null) return used;
        for (Item required : action.getConsumables()) {
            if (required == null) continue;
            String key = requiredKey(required);
            if (key == null) continue;
            Iterator<PlayerCharacter.PlayerItem> iterator = character.getItems().iterator();
            while (iterator.hasNext()) {
                PlayerCharacter.PlayerItem carried = iterator.next();
                if (carried != null && key.equals(normalize(carried.getItemId()))) {
                    if (carried.getQuantity() > 1) carried.setQuantity(carried.getQuantity() - 1);
                    else iterator.remove();
                    used.add(required.getName() == null ? required.getId() : required.getName());
                    break;
                }
            }
        }
        return used;
    }

    /**
     * Spells store required consumables as whole {@link Item} objects while characters carry
     * item <em>ids</em>, so the two are matched on id where the spell has one and on name
     * otherwise - hand-written spell components often only name what they need.
     */
    private static String requiredKey(Item item) {
        if (item.getId() != null && !item.getId().isBlank()) return normalize(item.getId());
        if (item.getName() != null && !item.getName().isBlank()) return normalize(item.getName());
        return null;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static PlayerCharacter characterOf(MapObject token) {
        return token instanceof PlayerToken player ? player.getCharacter() : null;
    }

    private static String describeTargets(List<MapObject> targets) {
        if (targets.size() == 1) return TokenSupport.nameOf(targets.get(0));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) builder.append(i == targets.size() - 1 ? " and " : ", ");
            builder.append(TokenSupport.nameOf(targets.get(i)));
        }
        return builder.toString();
    }
}
