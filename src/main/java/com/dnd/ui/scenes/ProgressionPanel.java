package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.model.character.CharacterClass;
import com.dnd.model.character.ClassFeature;
import com.dnd.model.character.PlayerCharacter;
import com.dnd.model.character.Progression;
import com.dnd.model.magic.CastingResource;
import com.dnd.model.magic.SpellSlots;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Level-up and rest controls for one character sheet, shared by the character screen and
 * the storyline editor's Manage Player panel. Every change is saved straight to the sheet,
 * which is also what the battle map reads, so there is a single source of truth.
 */
final class ProgressionPanel {

    private ProgressionPanel() {
    }

    static Node build(BaseScene owner, CampaignRepositories repos, PlayerCharacter pc, Runnable onChanged) {
        CharacterClass cls = pc.getClassId() == null ? null : repos.classes().getById(pc.getClassId());
        if (Progression.ensureSlots(pc, cls)) repos.players().save(pc);

        VBox box = new VBox(8);
        box.setPadding(new Insets(8, 0, 8, 0));
        box.getChildren().add(owner.sectionLabel("Progression & Rest"));

        List<String> facts = new ArrayList<>();
        facts.add("Level " + pc.getLevel() + (pc.getSubclass() == null || pc.getSubclass().isBlank() ? "" : " · " + pc.getSubclass()));
        facts.add("XP " + pc.getXp() + " / " + Progression.XP_PER_LEVEL);
        if (pc.getMaxHitPoints() > 0) facts.add("HP " + pc.getCurrentHitPoints() + "/" + pc.getMaxHitPoints());
        CastingResource resource = Progression.resourceOf(pc, cls);
        if (pc.getMaxMana() > 0) facts.add("Mana " + pc.getCurrentMana() + "/" + pc.getMaxMana());
        String slots = SpellSlots.describe(pc.getSpellSlots(), resource);
        if (!slots.isBlank()) facts.add((resource == CastingResource.ENERGY_CHARGES ? "Charges " : "Slots ") + slots);
        facts.add("Casts with: " + resource);
        if (!pc.getCooldowns().isEmpty()) facts.add("Cooling down: " + pc.getCooldowns());
        if (!pc.getActiveEffects().isEmpty()) facts.add("Effects: " + pc.getActiveEffects());
        Label summary = owner.body(String.join("   ·   ", facts));
        summary.setWrapText(true);
        box.getChildren().add(summary);

        Button levelUp = owner.btn("Level Up ▲", () -> openLevelUpDialog(owner, repos, pc, cls, onChanged));
        levelUp.setDisable(!Progression.canLevelUp(pc));
        levelUp.setTooltip(new Tooltip(Progression.canLevelUp(pc)
            ? "Preview and apply level " + (pc.getLevel() + 1)
            : "Needs " + Progression.XP_PER_LEVEL + " XP (has " + pc.getXp() + ")"));

        Button shortRest = owner.btn("Short Rest", () -> {
            TextInputDialog ask = new TextInputDialog("0");
            ask.setTitle("Short rest");
            ask.setHeaderText(null);
            ask.setContentText(pc.getName() + " - HP recovered from hit dice rolled:");
            owner.styleDialog(ask);
            ask.showAndWait().ifPresent(text -> {
                int hp;
                try {
                    hp = Math.max(0, Integer.parseInt(text.trim()));
                } catch (NumberFormatException ex) {
                    hp = 0;
                }
                Progression.shortRest(pc, cls, hp);
                repos.players().save(pc);
                onChanged.run();
            });
        });
        shortRest.setTooltip(new Tooltip("HP from hit dice, pact slots back, half the missing mana, timed effects end"));

        Button longRest = owner.btn("Long Rest", () -> {
            Progression.longRest(pc, repos.items()::getById);
            repos.players().save(pc);
            onChanged.run();
        });
        longRest.setTooltip(new Tooltip("Full HP, mana and spell slots; every effect and cooldown cleared."
            + " Energy charges are refilled by the workshop furnace from the fuel in the pack."));

        box.getChildren().add(new FlowPane(8, 8, levelUp, shortRest, longRest));
        return box;
    }

    /** Shows everything the next level brings, lets the DM override any number, then applies it. */
    static void openLevelUpDialog(BaseScene owner, CampaignRepositories repos, PlayerCharacter pc,
                                  CharacterClass cls, Runnable onChanged) {
        Progression.LevelUpPlan plan = Progression.plan(pc, cls);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Level Up");
        dialog.setHeaderText(pc.getName() + ": level " + plan.fromLevel + " → " + plan.toLevel);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;

        Spinner<Integer> hp = new Spinner<>(-50, 200, plan.hitPointGain);
        hp.setEditable(true);
        grid.addRow(row++, new Label("Max HP gain:"), hp,
            new Label("roll 1d" + plan.hitDieSides + " " + signed(plan.conModifier) + " CON (average shown)"));

        Spinner<Integer> mana = new Spinner<>(0, 200, plan.manaGain);
        mana.setEditable(true);
        if (plan.resource == CastingResource.MANA) {
            grid.addRow(row++, new Label("Max mana gain:"), mana);
        }

        List<Spinner<Integer>> slotSpinners = new ArrayList<>();
        if (plan.resource.usesSlots()) {
            for (int i = 0; i < 9; i++) {
                if (plan.slotsAfter[i] == 0 && plan.slotsBefore[i] == 0) {
                    slotSpinners.add(null);
                    continue;
                }
                Spinner<Integer> s = new Spinner<>(0, 9, plan.slotsAfter[i]);
                s.setEditable(true);
                slotSpinners.add(s);
                grid.addRow(row++, new Label(SpellSlots.rowLabel(i + 1, plan.resource) + ":"), s,
                    new Label("was " + plan.slotsBefore[i]));
            }
        }

        grid.addRow(row++, new Label("Proficiency bonus:"), new Label(signed(plan.proficiencyBonus)));
        if (plan.abilityScoreImprovement) {
            grid.addRow(row++, new Label("Ability scores:"),
                new Label("Ability Score Improvement - +2 to one score or +1 to two (edit stats on the sheet)"));
        }

        StringBuilder featureText = new StringBuilder();
        for (ClassFeature feature : plan.features) {
            featureText.append("• ").append(feature.getName());
            if (feature.getDescription() != null && !feature.getDescription().isBlank()) {
                featureText.append(" - ").append(feature.getDescription());
            }
            featureText.append('\n');
        }
        TextArea features = new TextArea(featureText.length() == 0
            ? "(no new class features at this level)" : featureText.toString().trim());
        features.setWrapText(true);
        features.setPrefRowCount(6);
        features.setEditable(false);

        VBox content = new VBox(10, grid, new Label("Unlocked:"), features,
            new Label("XP resets to 0. Current HP and mana grow by the same amount as the maximum."));
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        plan.hitPointGain = hp.getValue();
        plan.manaGain = plan.resource == CastingResource.MANA ? mana.getValue() : 0;
        for (int i = 0; i < slotSpinners.size(); i++) {
            if (slotSpinners.get(i) != null) plan.slotsAfter[i] = slotSpinners.get(i).getValue();
        }
        Progression.apply(pc, plan);
        repos.players().save(pc);
        onChanged.run();
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }
}
