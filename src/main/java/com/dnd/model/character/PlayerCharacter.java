package com.dnd.model.character;

import com.dnd.model.character.stats.CoreStats;
import com.dnd.model.interfaces.Printable;
import com.dnd.model.world.map.ActiveEffect;
import com.dnd.security.PasswordHasher;

import java.util.ArrayList;
import java.util.List;

public class PlayerCharacter implements Printable {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 30;

    private String id;
    private String name;
    private String classId;
    private String raceId;
    // Starts at the minimum valid level so a freshly-instantiated character (e.g. one built
    // by the reflective entity form before the DM fills in every field) is never left in an
    // invalid state that would fail validation again on the next load.
    private int level = MIN_LEVEL;
    private int xp;
    private CoreStats stats;
    private List<PlayerItem> items;
    private List<PlayerSpell> spells;
    /**
     * Salted hash of this character's player-mode access password (never the
     * plaintext password itself - see {@link PasswordHasher}). {@code null}
     * means no password has been set, so player mode won't gate access to
     * this character.
     */
    private String passwordHash;
    /** Random per-character salt paired with {@link #passwordHash}. */
    private String passwordSalt;

    public PlayerCharacter() {
    }

    public PlayerCharacter(String id, String name, String classId, String raceId, int level, CoreStats stats, List<PlayerItem> items, List<PlayerSpell> spells) {
        this.id = id;
        this.name = name;
        this.classId = classId;
        this.raceId = raceId;
        setLevel(level);
        this.stats = stats;
        this.items = items;
        this.spells = spells;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getRaceId() {
        return raceId;
    }

    public void setRaceId(String raceId) {
        this.raceId = raceId;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + " but was " + level);
        }
        this.level = level;
    }

    public int getXp() {
        return xp;
    }

    public void setXp(int xp) {
        if (xp < 0) {
            throw new IllegalArgumentException("XP cannot be negative but was " + xp);
        }
        this.xp = xp;
    }

    /** Adds (or, with a negative amount, removes) experience without letting it go below zero. */
    public void addXp(int amount) {
        setXp(Math.max(0, this.xp + amount));
    }

    public CoreStats getStats() {
        return stats;
    }

    public void setStats(CoreStats stats) {
        this.stats = stats;
    }

    public List<PlayerItem> getItems() {
        return items;
    }

    public void setItems(List<PlayerItem> items) {
        this.items = items;
    }

    public List<PlayerSpell> getSpells() {
        return spells;
    }

    public void setSpells(List<PlayerSpell> spells) {
        this.spells = spells;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public void setPasswordSalt(String passwordSalt) {
        this.passwordSalt = passwordSalt;
    }

    /** @return {@code true} if a password has been set for this character. */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    /** Hashes {@code rawPassword} with a freshly generated salt and stores the result. */
    public void setPassword(String rawPassword) {
        this.passwordSalt = PasswordHasher.generateSalt();
        this.passwordHash = PasswordHasher.hash(rawPassword, passwordSalt);
    }

    /**
     * @return {@code true} if {@code rawPassword} matches this character's stored
     *         password, or if no password has been set (open access).
     */
    public boolean checkPassword(String rawPassword) {
        if (!hasPassword()) {
            return true;
        }
        return PasswordHasher.matches(rawPassword, passwordSalt, passwordHash);
    }

    private String imagePath;
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    /** Name of the human player controlling this character (distinct from the character's own name). */
    private String playerName;
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    /**
     * Account username the DM has assigned this character to. When a player joins a hosted
     * session, this is what picks their character out of the roster automatically, so they
     * never see anyone else's sheet or the campaign list.
     */
    private String ownerUsername;
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    /** Free-text background or appearance notes; players may rewrite this themselves. */
    private String description;
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /** True when {@code username} is the account this character belongs to, ignoring case. */
    public boolean isOwnedBy(String username) {
        return ownerUsername != null && username != null && ownerUsername.equalsIgnoreCase(username.trim());
    }

    // ── Out-of-combat vitals ────────────────────────────────────────────────
    // These are the character's baseline, at-rest hit points/mana/effects, kept on the
    // persistent sheet so a DM can track them (heal between sessions, apply a lingering
    // curse, hand out potions, ...) from the storyline editor without needing a battle map
    // open. A freshly placed token on the battle map seeds its own (separately tracked,
    // in-combat) CombatState from these values - see TokenSupport.defaultCombatFor.

    private int maxHitPoints;
    private int currentHitPoints;
    private int maxMana;
    private int currentMana;
    private List<ActiveEffect> activeEffects = new ArrayList<>();

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
        if (currentHitPoints < 0) this.currentHitPoints = 0;
        else if (maxHitPoints > 0 && currentHitPoints > maxHitPoints) this.currentHitPoints = maxHitPoints;
        else this.currentHitPoints = currentHitPoints;
    }

    /** Adds (or, with a negative amount, removes) hit points, clamped to [0, max]. */
    public void applyDamage(int amount) {
        setCurrentHitPoints(currentHitPoints - Math.max(0, amount));
    }

    /** Restores hit points, clamped to the current max. */
    public void heal(int amount) {
        setCurrentHitPoints(currentHitPoints + Math.max(0, amount));
    }

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

    public List<ActiveEffect> getActiveEffects() {
        return activeEffects;
    }

    public void setActiveEffects(List<ActiveEffect> activeEffects) {
        this.activeEffects = activeEffects != null ? activeEffects : new ArrayList<>();
    }

    /** Applies a new lingering effect (e.g. "Frost, 3 rounds"). */
    public void addEffect(ActiveEffect effect) {
        if (activeEffects == null) activeEffects = new ArrayList<>();
        activeEffects.add(effect);
    }

    /** Removes an effect once the DM decides it has worn off. */
    public void clearEffect(ActiveEffect effect) {
        if (activeEffects != null) activeEffects.remove(effect);
    }

    // ── Class progression & magic ───────────────────────────────────────────

    /** Chosen subclass (e.g. "Path of the Berserker"); unlocks that subclass's features. */
    private String subclass;
    public String getSubclass() { return subclass; }
    public void setSubclass(String subclass) { this.subclass = subclass; }

    /** Long-form history, organised by the DM (Darius's rune journey lives here). */
    private String backstory;
    public String getBackstory() { return backstory; }
    public void setBackstory(String backstory) { this.backstory = backstory; }

    /**
     * What this character spends to cast. {@code null} means "whatever the class uses":
     * spell slots for standard casters. Homebrew characters can be switched to mana, or to
     * hit points for blood-magic style characters.
     */
    private com.dnd.model.magic.CastingResource castingResource;
    public com.dnd.model.magic.CastingResource getCastingResource() { return castingResource; }
    public void setCastingResource(com.dnd.model.magic.CastingResource castingResource) {
        this.castingResource = castingResource;
    }

    /** Spell slots by level; spent when casting, refilled on rests. */
    private List<com.dnd.model.magic.SpellSlot> spellSlots = new ArrayList<>();
    public List<com.dnd.model.magic.SpellSlot> getSpellSlots() { return spellSlots; }
    public void setSpellSlots(List<com.dnd.model.magic.SpellSlot> spellSlots) {
        this.spellSlots = spellSlots != null ? spellSlots : new ArrayList<>();
    }

    /** Blueprint item ids copied into this character's blueprint book (artificers). */
    private List<String> knownBlueprints = new ArrayList<>();
    public List<String> getKnownBlueprints() {
        if (knownBlueprints == null) knownBlueprints = new ArrayList<>();
        return knownBlueprints;
    }
    public void setKnownBlueprints(List<String> knownBlueprints) {
        this.knownBlueprints = knownBlueprints != null ? knownBlueprints : new ArrayList<>();
    }

    /** Fuel units already burning in this character's workshop furnace, not yet turned into charges. */
    private int furnaceFuel;
    public int getFurnaceFuel() { return furnaceFuel; }
    public void setFurnaceFuel(int furnaceFuel) { this.furnaceFuel = Math.max(0, furnaceFuel); }

    /** Spell/ability id → rounds left before it can be used again; persists between fights. */
    private java.util.Map<String, Integer> cooldowns = new java.util.LinkedHashMap<>();
    public java.util.Map<String, Integer> getCooldowns() { return cooldowns; }
    public void setCooldowns(java.util.Map<String, Integer> cooldowns) {
        this.cooldowns = cooldowns != null ? cooldowns : new java.util.LinkedHashMap<>();
    }

    /** Class/race feature id → uses (or pool points) spent since its last recharge. */
    private java.util.Map<String, Integer> featureUses = new java.util.LinkedHashMap<>();
    public java.util.Map<String, Integer> getFeatureUses() {
        if (featureUses == null) featureUses = new java.util.LinkedHashMap<>();
        return featureUses;
    }
    public void setFeatureUses(java.util.Map<String, Integer> featureUses) {
        this.featureUses = featureUses != null ? featureUses : new java.util.LinkedHashMap<>();
    }

    @Override
    public String toString() {
        return name != null ? name : id;
    }

    public static class PlayerItem implements Printable {
        private String itemId;
        private ItemCondition condition;
        private boolean equipped;

        public PlayerItem() {
        }

        public PlayerItem(String itemId, ItemCondition condition, boolean equipped) {
            this.itemId = itemId;
            this.condition = condition;
            this.equipped = equipped;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public ItemCondition getCondition() {
            return condition;
        }

        public void setCondition(ItemCondition condition) {
            this.condition = condition;
        }

        public boolean isEquipped() {
            return equipped;
        }

        public void setEquipped(boolean equipped) {
            this.equipped = equipped;
        }

        /** How many are carried in this stack (bullets, arrows, potions...). Never below 1. */
        private int quantity = 1;

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = Math.max(1, quantity);
        }

        @Override
        public String toString() {
            return itemId + (quantity > 1 ? " ×" + quantity : "") + (equipped ? " (equipped)" : "");
        }
    }

    public static class ItemCondition implements Printable {
        private int durability;

        public ItemCondition() {
        }

        public ItemCondition(int durability) {
            this.durability = durability;
        }

        public int getDurability() {
            return durability;
        }

        public void setDurability(int durability) {
            this.durability = durability;
        }

        @Override
        public String toString() {
            return "Durability: " + durability;
        }
    }

    public static class PlayerSpell implements Printable {
        private String spellId;
        private int rank;
        private boolean active;

        public PlayerSpell() {
        }

        public PlayerSpell(String spellId, int rank, boolean active) {
            this.spellId = spellId;
            this.rank = rank;
            this.active = active;
        }

        public String getSpellId() {
            return spellId;
        }

        public void setSpellId(String spellId) {
            this.spellId = spellId;
        }

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        @Override
        public String toString() {
            return spellId + " (Rank " + rank + ")" + (active ? "" : " [inactive]");
        }
    }
}

