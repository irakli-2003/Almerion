package com.dnd.model.world.map;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable, per-token state of a battle: hit points, mana, money, initiative and
 * dying/dead status.
 *
 * <p>This deliberately lives on the <em>token</em> rather than on the catalog entity it
 * references. A single "Goblin" catalog entry can be placed on a map five times, and each
 * of those goblins takes damage independently; writing hit points back into the catalog
 * would make them share one pool and would corrupt the campaign's reusable bestiary.
 * Because tokens are serialized inside the map, battle state persists with the map.</p>
 */
public class CombatState {

    /** A creature gets three failed death saves before it dies, per the usual tabletop rule. */
    public static final int MAX_DEATH_SAVES = 3;

    /** Feet of walking movement per round assumed for a creature nobody has configured. */
    public static final int DEFAULT_WALK_SPEED = 30;

    /** One square of the battle grid is five feet, the usual tabletop scale. */
    public static final int FEET_PER_SQUARE = 5;

    private int maxHitPoints;
    private int currentHitPoints;
    private int maxMana;
    private int currentMana;
    private int gold;
    private int initiative;
    /** Number of failed death saves so far, 0..{@link #MAX_DEATH_SAVES}. */
    private int deathSaveFailures;
    private boolean downed;
    private boolean dead;
    private List<String> conditions = new ArrayList<>();
    private String notes;
    /** False for tokens that are scenery or loot rather than participants in turn order. */
    private boolean inInitiative = true;

    /**
     * Movement rates in feet per round. Walking defaults to 30, the speed of most
     * playable races; a zero climb or swim speed means the creature has no special
     * mode for that terrain and crosses it at the difficult-terrain rate.
     */
    private int walkSpeed = DEFAULT_WALK_SPEED;
    private int climbSpeed;
    private int swimSpeed;
    /**
     * Set once the speeds have been filled in from the creature's race or stat block, so a
     * later hand-edit by the DM is never silently overwritten by re-seeding.
     */
    private boolean speedSeeded;
    /**
     * Squares of movement already spent this round. Reset when the turn advances, so
     * it is genuinely per-turn rather than a running total for the whole battle.
     */
    private int movementUsed;

    public CombatState() {
    }

    public CombatState(int maxHitPoints) {
        this.maxHitPoints = Math.max(0, maxHitPoints);
        this.currentHitPoints = this.maxHitPoints;
    }

    // ── Hit points ──────────────────────────────────────────────────────────

    public int getMaxHitPoints() {
        return maxHitPoints;
    }

    public void setMaxHitPoints(int maxHitPoints) {
        this.maxHitPoints = Math.max(0, maxHitPoints);
        if (currentHitPoints > this.maxHitPoints) currentHitPoints = this.maxHitPoints;
    }

    public int getCurrentHitPoints() {
        return currentHitPoints;
    }

    public void setCurrentHitPoints(int currentHitPoints) {
        this.currentHitPoints = clampHitPoints(currentHitPoints);
    }

    /**
     * Applies damage (positive) or healing (negative) and updates dying/dead status.
     *
     * <p>Dropping to zero knocks a creature down rather than killing it outright, which is
     * what makes the death-save countdown meaningful. Healing a downed creature above zero
     * revives it and clears its accumulated failed saves.</p>
     */
    public void applyDamage(int amount) {
        setCurrentHitPoints(currentHitPoints - amount);
        if (currentHitPoints <= 0) {
            if (!dead) downed = true;
        } else {
            downed = false;
            dead = false;
            deathSaveFailures = 0;
            deathSaveSuccesses = 0;
        }
    }

    public void heal(int amount) {
        applyDamage(-Math.abs(amount));
    }

    private int clampHitPoints(int value) {
        if (value < 0) return 0;
        if (maxHitPoints > 0 && value > maxHitPoints) return maxHitPoints;
        return value;
    }

    // ── Death saves ─────────────────────────────────────────────────────────

    public int getDeathSaveFailures() {
        return deathSaveFailures;
    }

    private int deathSaveSuccesses;

    public int getDeathSaveSuccesses() { return deathSaveSuccesses; }

    public void setDeathSaveSuccesses(int successes) {
        deathSaveSuccesses = Math.max(0, Math.min(MAX_DEATH_SAVES, successes));
    }

    public void setDeathSaveFailures(int deathSaveFailures) {
        this.deathSaveFailures = Math.max(0, Math.min(MAX_DEATH_SAVES, deathSaveFailures));
        this.dead = this.deathSaveFailures >= MAX_DEATH_SAVES;
        if (this.dead) downed = false;
    }

    /** Records a failed death save; the third one kills the creature. */
    public void failDeathSave() {
        setDeathSaveFailures(deathSaveFailures + 1);
        if (!dead) downed = true;
    }

    /** Successes and failures are independent; the UI requests rolled HP on the third success. */
    public void succeedDeathSave() {
        if (currentHitPoints <= 0 && !dead) setDeathSaveSuccesses(deathSaveSuccesses + 1);
    }

    /**
     * How many failed saves the creature can still absorb, which is the number shown on its
     * token while it is dying. Zero means dead, and the token shows a cross instead.
     */
    public int remainingDeathSaves() {
        return Math.max(0, MAX_DEATH_SAVES - deathSaveFailures);
    }

    public boolean isDowned() {
        return downed && !dead;
    }

    public void setDowned(boolean downed) {
        this.downed = downed;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
        if (dead) {
            downed = false;
            deathSaveFailures = MAX_DEATH_SAVES;
            currentHitPoints = 0;
        }
    }

    /** Fully restores a creature: alive, unhurt, and with its death saves cleared. */
    public void revive() {
        dead = false;
        downed = false;
        deathSaveFailures = 0;
        deathSaveSuccesses = 0;
        currentHitPoints = maxHitPoints > 0 ? maxHitPoints : 1;
    }

    public void revive(int rolledHp) {
        if (rolledHp < 1 || rolledHp > 4) throw new IllegalArgumentException("Revival requires a 1d4 result (1-4).");
        revive();
        setCurrentHitPoints(rolledHp);
    }

    /** A dead creature is skipped by the initiative order but stays visible on the map. */
    public boolean isActingThisRound() {
        return !dead;
    }

    // ── Mana, money, initiative ─────────────────────────────────────────────

    public int getMaxMana() {
        return maxMana;
    }

    public void setMaxMana(int maxMana) {
        this.maxMana = Math.max(0, maxMana);
        if (currentMana > this.maxMana) currentMana = this.maxMana;
    }

    public int getCurrentMana() {
        return currentMana;
    }

    public void setCurrentMana(int currentMana) {
        if (currentMana < 0) this.currentMana = 0;
        else if (maxMana > 0 && currentMana > maxMana) this.currentMana = maxMana;
        else this.currentMana = currentMana;
    }

    public int getGold() {
        return gold;
    }

    public void setGold(int gold) {
        this.gold = Math.max(0, gold);
    }

    public int getInitiative() {
        return initiative;
    }

    public void setInitiative(int initiative) {
        this.initiative = initiative;
    }

    // ── Conditions & notes ──────────────────────────────────────────────────

    public List<String> getConditions() {
        return conditions;
    }

    public void setConditions(List<String> conditions) {
        this.conditions = conditions != null ? conditions : new ArrayList<>();
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isInInitiative() {
        return inInitiative;
    }

    public void setInInitiative(boolean inInitiative) {
        this.inInitiative = inInitiative;
    }

    // ── Movement ────────────────────────────────────────────────────────────

    public int getWalkSpeed() {
        return walkSpeed;
    }

    public boolean isSpeedSeeded() {
        return speedSeeded;
    }

    public void setSpeedSeeded(boolean speedSeeded) {
        this.speedSeeded = speedSeeded;
    }

    public void setWalkSpeed(int walkSpeed) {
        this.walkSpeed = Math.max(0, walkSpeed);
    }

    public int getClimbSpeed() {
        return climbSpeed;
    }

    public void setClimbSpeed(int climbSpeed) {
        this.climbSpeed = Math.max(0, climbSpeed);
    }

    public int getSwimSpeed() {
        return swimSpeed;
    }

    public void setSwimSpeed(int swimSpeed) {
        this.swimSpeed = Math.max(0, swimSpeed);
    }

    public int getMovementUsed() {
        return movementUsed;
    }

    public void setMovementUsed(int movementUsed) {
        this.movementUsed = Math.max(0, movementUsed);
    }

    public boolean hasClimbSpeed() {
        return climbSpeed > 0;
    }

    public boolean hasSwimSpeed() {
        return swimSpeed > 0;
    }

    /** Total squares this creature may cover in one turn, walking. */
    public int movementSquares() {
        return walkSpeed / FEET_PER_SQUARE;
    }

    /** Squares still available this turn after what has already been spent. */
    public int movementRemaining() {
        return Math.max(0, movementSquares() - movementUsed);
    }

    /** Called when the turn passes to this creature, giving it a fresh movement allowance. */
    public void resetMovement() {
        movementUsed = 0;
    }

    // ── Effects and cooldowns ───────────────────────────────────────────────

    /**
     * Effects currently running on this creature. Kept on the combat state rather than on
     * the catalog entry so two orcs hit by the same spell can burn out independently.
     */
    private List<ActiveEffect> activeEffects = new ArrayList<>();

    /**
     * Rounds left before each spell or ability can be used again, keyed by its id. Entries
     * are removed once they reach zero, so an empty map means everything is ready.
     */
    private Map<String, Integer> cooldowns = new LinkedHashMap<>();

    public List<ActiveEffect> getActiveEffects() {
        if (activeEffects == null) activeEffects = new ArrayList<>();
        return activeEffects;
    }

    public void setActiveEffects(List<ActiveEffect> activeEffects) {
        this.activeEffects = activeEffects != null ? activeEffects : new ArrayList<>();
    }

    public Map<String, Integer> getCooldowns() {
        if (cooldowns == null) cooldowns = new LinkedHashMap<>();
        return cooldowns;
    }

    public void setCooldowns(Map<String, Integer> cooldowns) {
        this.cooldowns = cooldowns != null ? cooldowns : new LinkedHashMap<>();
    }

    // ── Class & race features ───────────────────────────────────────────────

    /** Feature id → uses (or pool points) spent since it last recharged, e.g. "rage" → 1. */
    private Map<String, Integer> featureUses = new LinkedHashMap<>();
    /** Damage type ids this creature always halves (race traits and the like). */
    private List<String> resistances = new ArrayList<>();
    /**
     * Id of a once-per-rest "drop to 1 HP instead of 0" trait (Relentless Endurance), or
     * null. Whether it is still available is tracked in {@link #featureUses}.
     */
    private String enduranceFeature;

    public Map<String, Integer> getFeatureUses() {
        if (featureUses == null) featureUses = new LinkedHashMap<>();
        return featureUses;
    }

    public void setFeatureUses(Map<String, Integer> featureUses) {
        this.featureUses = featureUses != null ? featureUses : new LinkedHashMap<>();
    }

    public List<String> getResistances() {
        if (resistances == null) resistances = new ArrayList<>();
        return resistances;
    }

    public void setResistances(List<String> resistances) {
        this.resistances = resistances != null ? new ArrayList<>(resistances) : new ArrayList<>();
    }

    public String getEnduranceFeature() {
        return enduranceFeature;
    }

    /** Walking speed currently added by class features, so a level-up can adjust it by the difference. */
    private int featureSpeedBonus;

    public int getFeatureSpeedBonus() {
        return featureSpeedBonus;
    }

    public void setFeatureSpeedBonus(int featureSpeedBonus) {
        this.featureSpeedBonus = featureSpeedBonus;
    }

    public void setEnduranceFeature(String enduranceFeature) {
        this.enduranceFeature = enduranceFeature;
    }

    /** True when innate traits or a running effect halve damage of {@code typeId}. */
    public boolean resists(String typeId) {
        if (typeId == null || typeId.isBlank()) return false;
        for (String r : getResistances()) if (typeId.equalsIgnoreCase(r)) return true;
        for (ActiveEffect e : getActiveEffects()) {
            for (String r : e.getResistances()) if (typeId.equalsIgnoreCase(r)) return true;
        }
        return false;
    }

    /** Sum of Strength-melee damage bonuses from running effects (Rage). */
    public int meleeDamageBonus() {
        int total = 0;
        for (ActiveEffect e : getActiveEffects()) total += e.getMeleeDamageBonus();
        return total;
    }

    /** Sum of AC changes from running effects. */
    public int effectAcBonus() {
        int total = 0;
        for (ActiveEffect e : getActiveEffects()) total += e.getAcBonus();
        return total;
    }

    /**
     * Adds an effect, refreshing the duration instead of stacking a second copy when the
     * same effect is already running - being frozen twice makes it last longer, not tick
     * twice as hard.
     */
    public void addEffect(ActiveEffect effect) {
        if (effect == null || effect.isExpired()) return;
        for (ActiveEffect existing : getActiveEffects()) {
            if (existing.getEffectId() != null && existing.getEffectId().equals(effect.getEffectId())) {
                existing.setRemainingRounds(Math.max(existing.getRemainingRounds(), effect.getRemainingRounds()));
                return;
            }
        }
        getActiveEffects().add(effect);
    }

    /** Rounds left on a spell or ability, or zero when it is ready to use. */
    public int cooldownFor(String actionId) {
        Integer left = getCooldowns().get(actionId);
        return left == null ? 0 : Math.max(0, left);
    }

    public boolean isOnCooldown(String actionId) {
        return cooldownFor(actionId) > 0;
    }

    /** Puts a spell or ability out of action for {@code rounds}; zero rounds is a no-op. */
    public void startCooldown(String actionId, int rounds) {
        if (actionId == null || rounds <= 0) return;
        getCooldowns().put(actionId, rounds);
    }

    /** Counts every cooldown down by one round, dropping the ones that have recovered. */
    public void tickCooldowns() {
        Iterator<Map.Entry<String, Integer>> iterator = getCooldowns().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            int left = entry.getValue() == null ? 0 : entry.getValue() - 1;
            if (left <= 0) {
                iterator.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    /**
     * Runs one round of every active effect: applies its per-round damage or healing, then
     * counts it down and drops the ones that have worn off.
     *
     * @return a human-readable line per effect that did something, for the DM's log
     */
    public List<String> tickEffects(String bearerName) {
        List<String> log = new ArrayList<>();
        Iterator<ActiveEffect> iterator = getActiveEffects().iterator();
        while (iterator.hasNext()) {
            ActiveEffect effect = iterator.next();
            if (effect.getDamagePerRound() > 0) {
                applyDamage(effect.getDamagePerRound());
                log.add(bearerName + " takes " + effect.getDamagePerRound() + " from " + effect.getName() + ".");
            }
            if (effect.getHealingPerRound() > 0) {
                heal(effect.getHealingPerRound());
                log.add(bearerName + " recovers " + effect.getHealingPerRound() + " from " + effect.getName() + ".");
            }
            if (effect.tick()) {
                iterator.remove();
                log.add(effect.getName() + " wears off " + bearerName + ".");
            }
        }
        return log;
    }

    // ── Spellcasting resources ──────────────────────────────────────────────

    /** Explicit casting currency; {@code null} means slots if the creature has any, else mana. */
    private com.dnd.model.magic.CastingResource castingResource;
    private List<com.dnd.model.magic.SpellSlot> spellSlots = new ArrayList<>();

    public com.dnd.model.magic.CastingResource getCastingResource() {
        return castingResource;
    }

    public void setCastingResource(com.dnd.model.magic.CastingResource castingResource) {
        this.castingResource = castingResource;
    }

    public List<com.dnd.model.magic.SpellSlot> getSpellSlots() {
        if (spellSlots == null) spellSlots = new ArrayList<>();
        return spellSlots;
    }

    public void setSpellSlots(List<com.dnd.model.magic.SpellSlot> spellSlots) {
        this.spellSlots = spellSlots != null ? spellSlots : new ArrayList<>();
    }

    /** What this creature actually pays with when casting a levelled spell. */
    public com.dnd.model.magic.CastingResource effectiveCastingResource() {
        if (castingResource != null) return castingResource;
        return getSpellSlots().isEmpty() ? com.dnd.model.magic.CastingResource.MANA
            : com.dnd.model.magic.CastingResource.SPELL_SLOTS;
    }

    /** Deep copy, used for round snapshots so rewinding cannot share mutable lists. */
    public CombatState copy() {
        CombatState c = new CombatState();
        c.maxHitPoints = maxHitPoints;
        c.currentHitPoints = currentHitPoints;
        c.maxMana = maxMana;
        c.currentMana = currentMana;
        c.gold = gold;
        c.initiative = initiative;
        c.deathSaveFailures = deathSaveFailures;
        c.deathSaveSuccesses = deathSaveSuccesses;
        c.downed = downed;
        c.dead = dead;
        c.conditions = new ArrayList<>(getConditions());
        c.notes = notes;
        c.inInitiative = inInitiative;
        c.walkSpeed = walkSpeed;
        c.climbSpeed = climbSpeed;
        c.swimSpeed = swimSpeed;
        c.speedSeeded = speedSeeded;
        c.movementUsed = movementUsed;
        List<ActiveEffect> effects = new ArrayList<>();
        for (ActiveEffect e : getActiveEffects()) effects.add(e.copy());
        c.activeEffects = effects;
        c.cooldowns = new LinkedHashMap<>(getCooldowns());
        c.featureUses = new LinkedHashMap<>(getFeatureUses());
        c.resistances = new ArrayList<>(getResistances());
        c.enduranceFeature = enduranceFeature;
        c.featureSpeedBonus = featureSpeedBonus;
        c.castingResource = castingResource;
        c.spellSlots = com.dnd.model.magic.SpellSlots.copy(getSpellSlots());
        return c;
    }

    /** Fraction of max hit points remaining, in 0..1, for drawing health bars. */
    public double healthFraction() {
        if (maxHitPoints <= 0) return 0;
        return Math.max(0, Math.min(1, currentHitPoints / (double) maxHitPoints));
    }

    public double manaFraction() {
        if (maxMana <= 0) return 0;
        return Math.max(0, Math.min(1, currentMana / (double) maxMana));
    }
}
