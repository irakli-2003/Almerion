package com.dnd.model.character;

import com.dnd.model.interfaces.Printable;

/**
 * Something a class (or one of its subclasses) unlocks at a given level - Rage, Extra
 * Attack, an Ability Score Improvement, and so on. Shown in the level-up panel and copied
 * onto the character's ability list when they reach that level.
 */
public class ClassFeature implements Printable {
    private int level = 1;
    private String name;
    private String description;
    /** Subclass this belongs to, or blank for features every member of the class gets. */
    private String subclass;
    private int usesPerRest;
    private boolean shortRestRecharge;

    public int getUsesPerRest() { return usesPerRest; }
    public void setUsesPerRest(int value) { usesPerRest = value; }
    public boolean isShortRestRecharge() { return shortRestRecharge; }
    public void setShortRestRecharge(boolean value) { shortRestRecharge = value; }

    public ClassFeature copy() {
        var copy = new ClassFeature(level, name, description, subclass);
        copy.setUsesPerRest(usesPerRest); copy.setShortRestRecharge(shortRestRecharge);
        return copy;
    }

    public ClassFeature() {
    }

    public ClassFeature(int level, String name, String description) {
        this(level, name, description, null);
    }

    public ClassFeature(int level, String name, String description, String subclass) {
        this.level = level;
        this.name = name;
        this.description = description;
        this.subclass = subclass;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, Math.min(20, level));
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSubclass() {
        return subclass;
    }

    public void setSubclass(String subclass) {
        this.subclass = subclass;
    }

    /** True when a character with {@code chosenSubclass} gets this feature. */
    public boolean appliesTo(String chosenSubclass) {
        return subclass == null || subclass.isBlank()
            || (chosenSubclass != null && subclass.equalsIgnoreCase(chosenSubclass.trim()));
    }

    @Override
    public String toString() {
        return "Lv " + level + " - " + name + (subclass == null || subclass.isBlank() ? "" : " (" + subclass + ")");
    }
}
