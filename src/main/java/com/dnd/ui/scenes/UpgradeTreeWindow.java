package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.*;
import com.dnd.ui.ImageStore;
import com.dnd.ui.components.EntityForm;
import com.dnd.ui.components.ImageFolders;
import com.dnd.ui.i18n.UiTranslator;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;

public final class UpgradeTreeWindow {
    private UpgradeTreeWindow() { }

    public static void showEditor(BaseScene owner, CampaignRepositories repos) {
        List<CharacterClass> classes = repos.classes().list();
        if (classes.isEmpty()) { error(owner, "Create a class before editing its upgrade tree."); return; }
        ChoiceDialog<CharacterClass> choose = new ChoiceDialog<>(classes.getFirst(), classes);
        choose.setTitle("Class Upgrade Trees");
        choose.setHeaderText("Choose the class whose tree you want to edit.");
        owner.styleDialog(choose);
        choose.showAndWait().ifPresent(cls -> edit(owner, repos, cls));
    }

    static void edit(BaseScene owner, CampaignRepositories repos, CharacterClass original) {
        if (Window.getWindows().stream().anyMatch(w -> w.getProperties().containsKey("almerion.map"))) {
            error(owner, "Close battle windows before changing upgrade trees.");
            return;
        }
        var mapper = JsonMappers.forCampaign(repos.campaignRoot());
        CharacterClass draft = mapper.convertValue(original, CharacterClass.class);
        Dialog<ButtonType> dialog = dialog(owner, original.getName() + " - " + UiTranslator.text("Class Upgrade Tree"));
        BorderPane graph = new BorderPane();
        Label message = owner.body("");
        message.setWrapText(true);
        Runnable refresh = () -> refreshGraph(graph, owner, repos, draft);
        Button branch = owner.btn("Add Branch", () -> {
            TextInputDialog ask = new TextInputDialog();
            ask.setTitle("Branch name");
            owner.styleDialog(ask);
            ask.showAndWait().filter(s -> !s.isBlank()).ifPresent(name -> {
                var b = new UpgradeTree.Branch();
                b.setId("branch-" + UUID.randomUUID()); b.setName(name.strip());
                draft.getUpgradeTree().getBranches().add(b); refresh.run();
            });
        });
        Button editBranch = owner.btn("Edit Branch", () -> {
            var branches = draft.getUpgradeTree().getBranches();
            if (branches.isEmpty()) return;
            ChoiceDialog<UpgradeTree.Branch> ask = new ChoiceDialog<>(branches.getFirst(), branches);
            ask.setTitle("Edit Branch"); owner.styleDialog(ask);
            ask.showAndWait().ifPresent(b -> {
                TextInputDialog rename = new TextInputDialog(b.getName());
                rename.setTitle("Branch name"); owner.styleDialog(rename);
                rename.showAndWait().filter(s -> !s.isBlank()).ifPresent(s -> { b.setName(s.strip()); refresh.run(); });
            });
        });
        Button deleteBranch = owner.btn("Delete Branch", () -> {
            var branches = draft.getUpgradeTree().getBranches();
            if (branches.isEmpty()) return;
            ChoiceDialog<UpgradeTree.Branch> ask = new ChoiceDialog<>(branches.getFirst(), branches);
            ask.setTitle("Delete Branch"); owner.styleDialog(ask);
            ask.showAndWait().ifPresent(b -> {
                if (draft.getUpgradeTree().getNodes().stream().anyMatch(n -> b.getId().equals(n.getBranchId()))) {
                    error(owner, "Move or delete this branch's nodes first."); return;
                }
                branches.remove(b); refresh.run();
            });
        });
        Button add = owner.btn("Add Node", () -> {
            if (draft.getUpgradeTree().getBranches().isEmpty()) { error(owner, "Add a named branch first."); return; }
            var n = new UpgradeTree.Node();
            n.setId("node-" + UUID.randomUUID());
            n.setBranchId(draft.getUpgradeTree().getBranches().getFirst().getId());
            if (editNode(owner, repos, draft, n, true)) refresh.run();
        });
        Label hint = owner.body("Each level-up earns one choice. All prerequisites are required; branches may mix."
            + " Exclusive links block both directions. IDs stay stable when renamed."
            + " Spell rewards can grant any GM-created ability from Worldbuilding > Spells."
            + " Every node grants a named feat or ability as well as any numeric rewards."
            + " GM-resolved feats state their action, limits and effect; granted spells use the spell system."
            + " Saved unlocks keep their original rewards when the tree changes.");
        hint.setWrapText(true);
        VBox content = new VBox(10, hint, new FlowPane(8, 8, branch, editBranch, deleteBranch, add), graph, message);
        VBox.setVgrow(graph, Priority.ALWAYS);
        content.setPrefSize(1000, 680);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                UpgradeRules.validateBranching(draft.getUpgradeTree());
                validateRewards(repos, draft.getUpgradeTree());
                CharacterClass live = repos.classes().getById(original.getId());
                if (live == null) throw new IllegalArgumentException("This class no longer exists.");
                if (!mapper.valueToTree(original.getUpgradeTree()).equals(mapper.valueToTree(live.getUpgradeTree()))) {
                    throw new IllegalArgumentException("This character or class changed. Reopen the upgrade tree before saving.");
                }
                // Consume old levels before the new definitions can be mistaken for new choices.
                for (var pc : repos.players().list()) {
                    if (Objects.equals(pc.getClassId(), original.getId()) && UpgradeRules.initialize(pc, original)) {
                        repos.players().save(pc);
                    }
                }
                live.setUpgradeTree(draft.getUpgradeTree());
                repos.classes().save(live);
            } catch (RuntimeException ex) { message.setText(ex.getMessage()); event.consume(); }
        });
        refresh.run();
        dialog.showAndWait();
    }

    private static void refreshGraph(BorderPane graph, BaseScene owner, CampaignRepositories repos, CharacterClass cls) {
        graph.setCenter(graphPanel(new UpgradeTreeView(repos.campaignRoot(), cls, null, n -> {
            if (UpgradeRules.ROOT.equals(n.getId())) { owner.showInfo(n.getName(), n.getDescription()); return; }
            if (editNode(owner, repos, cls, n, false)) refreshGraph(graph, owner, repos, cls);
        })));
    }

    private static boolean editNode(BaseScene owner, CampaignRepositories repos, CharacterClass cls,
                                     UpgradeTree.Node original, boolean isNew) {
        var draft = JsonMappers.forCampaign(repos.campaignRoot()).convertValue(original, UpgradeTree.Node.class);
        var tree = cls.getUpgradeTree();
        var catalogs = new LinkedHashMap<>(EntityDetailScene.catalogsFor(repos));
        catalogs.put("branchId", tree.getBranches().stream().map(b -> new EntityForm.Ref(b.getId(), b.getName())).toList());
        var links = tree.getNodes().stream().filter(n -> !n.getId().equals(draft.getId()))
            .map(n -> new EntityForm.Ref(n.getId(), n.getName())).toList();
        var prerequisites = new ArrayList<>(links);
        prerequisites.addFirst(new EntityForm.Ref(UpgradeRules.ROOT, UpgradeRules.foundation(cls, null).getName()));
        catalogs.put("prerequisites", prerequisites); catalogs.put("exclusiveWith", links);
        catalogs.put("spellIds", repos.spells().list().stream().map(s -> new EntityForm.Ref(s.getId(), s.getName())).toList());
        List<EntityForm.Ref> abilities = new ArrayList<>();
        abilities.add(new EntityForm.Ref("", "(none)"));
        repos.statSchema().getAbilities().forEach(a -> abilities.add(new EntityForm.Ref(a.id(), a.name())));
        catalogs.put("abilityId", abilities);
        EntityForm form = EntityForm.of(UpgradeTree.Node.class, draft, Set.of("id", "imagePath"), catalogs);
        Label message = owner.body("");
        message.setWrapText(true);
        Label picture = owner.body(Objects.toString(draft.getImagePath(), ""));
        Button image = owner.btn("Upload Node Picture", () -> {
            var file = ImageFolders.imageChooser("Select node picture").showOpenDialog(null);
            if (file == null) return;
            try {
                var loaded = new javafx.scene.image.Image(file.toURI().toString(), 48, 48, true, true);
                if (loaded.isError()) throw new IllegalArgumentException("The selected file is not a supported image.");
                draft.setImagePath(ImageStore.copyImage(repos.campaignRoot(), "upgrades", UUID.randomUUID().toString(), file));
                picture.setText(draft.getImagePath());
                message.setText("");
            } catch (java.io.IOException | RuntimeException ex) { message.setText(ex.getMessage()); }
        });
        Button clear = owner.btn("Clear Picture", () -> { draft.setImagePath(null); picture.setText(""); });
        Dialog<ButtonType> dialog = dialog(owner, isNew ? "Add Node" : original.getName());
        ButtonType delete = new ButtonType("Delete Node", ButtonBar.ButtonData.OTHER);
        if (!isNew) dialog.getDialogPane().getButtonTypes().add(0, delete);
        VBox content = new VBox(10, new FlowPane(8, 8, image, clear), picture, form.getNode(), message);
        ScrollPane scroll = scroll(content); scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(850); scroll.setPrefViewportHeight(600);
        dialog.getDialogPane().setContent(scroll);
        boolean[] saved = {false};
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            List<UpgradeTree.Node> before = new ArrayList<>(tree.getNodes());
            try {
                form.writeTo(draft);
                tree.getNodes().removeIf(n -> n.getId().equals(original.getId()));
                tree.getNodes().add(draft);
                UpgradeRules.validate(tree);
                validateReward(repos, draft);
                saved[0] = true;
            } catch (RuntimeException ex) { tree.setNodes(before); message.setText(ex.getMessage()); event.consume(); }
        });
        if (!isNew) dialog.getDialogPane().lookupButton(delete).addEventFilter(ActionEvent.ACTION, event -> {
            if (tree.getNodes().stream().anyMatch(n -> n.getPrerequisites().contains(original.getId())
                    || n.getExclusiveWith().contains(original.getId()))) {
                message.setText(UiTranslator.text("Remove links to this node before deleting it."));
                event.consume(); return;
            }
            tree.getNodes().remove(original); saved[0] = true;
        });
        dialog.showAndWait();
        return saved[0];
    }

    private static void validateRewards(CampaignRepositories repos, UpgradeTree tree) {
        for (var n : tree.getNodes()) {
            validateReward(repos, n);
        }
    }

    private static void validateReward(CampaignRepositories repos, UpgradeTree.Node n) {
        if (n.getReward().getFeatures().isEmpty() && n.getReward().getSpellIds().isEmpty()) {
            throw new IllegalArgumentException("Every upgrade node must grant a named feat or ability: " + n.getName());
        }
        for (String id : n.getReward().getSpellIds()) {
            if (repos.spells().getById(id) == null) throw new IllegalArgumentException("Unknown spell or ability: " + id);
        }
        var r = n.getReward();
        if (r.getAbilityIncrease() > 0 && repos.statSchema().getAbilities().stream().noneMatch(a -> a.id().equals(r.getAbilityId()))) {
            throw new IllegalArgumentException("Unknown ability score: " + r.getAbilityId());
        }
    }

    public static void showCharacter(BaseScene owner, CampaignRepositories repos, PlayerCharacter pc, Runnable changed) {
        showCharacter(owner, repos, pc, changed, true);
    }

    public static void showCharacter(BaseScene owner, CampaignRepositories repos, PlayerCharacter pc, Runnable changed, boolean canUnlock) {
        CharacterClass cls = repos.classes().getById(pc.getClassId());
        if (cls == null) { error(owner, "This character needs an existing class."); return; }
        try {
            if (UpgradeRules.initialize(pc, cls) && canUnlock) repos.players().save(pc);
            UpgradeRules.validate(cls.getUpgradeTree());
        } catch (RuntimeException ex) { error(owner, ex.getMessage()); return; }
        Dialog<ButtonType> dialog = dialog(owner, "Class Upgrade Tree");
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        VBox content = new VBox(10);
        content.setPrefSize(1000, 680);
        Runnable[] refresh = {null};
        refresh[0] = () -> {
            Label budget = owner.body(UiTranslator.text("Available unlocks:") + " " + UpgradeRules.available(pc));
            Label note = owner.body("One choice per level-up. Existing training has already spent older levels; its abilities are not applied twice.");
            note.setWrapText(true);
            var graph = new UpgradeTreeView(repos.campaignRoot(), cls, pc, n -> {
                if (UpgradeRules.ROOT.equals(n.getId())) { owner.showInfo(n.getName(), n.getDescription()); return; }
                String reason = UpgradeRules.blockedReason(pc, cls, n);
                if (!canUnlock && reason.isBlank()) reason = UiTranslator.text("Ask your GM to apply this upgrade.");
                var earned = pc.getUpgrades().getUnlocks().stream().filter(u -> Objects.equals(cls.getId(), u.getClassId())
                    && Objects.equals(n.getId(), u.getNodeId())).findFirst();
                String description = earned.map(UpgradeProgress.Unlock::getDescription).orElse(n.getDescription());
                var reward = earned.map(UpgradeProgress.Unlock::getReward).orElse(n.getReward());
                Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
                ask.setTitle(n.getName());
                ask.setHeaderText(n.getName());
                ask.setContentText(Objects.toString(description, "") + "\n" + rewardText(reward)
                    + (reason.isBlank() ? "\n" + UiTranslator.text("Spend one unlock on this node?") : "\n" + UiTranslator.text(reason)));
                if (!reason.isBlank() || !canUnlock) ask.getButtonTypes().setAll(ButtonType.CLOSE);
                owner.styleDialog(ask);
                if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                try {
                    if (Window.getWindows().stream().anyMatch(w -> w.getProperties().containsKey("almerion.map"))) {
                        throw new IllegalArgumentException("Close battle windows before unlocking upgrades.");
                    }
                    var mapper = JsonMappers.forCampaign(repos.campaignRoot());
                    PlayerCharacter draft = repos.players().getById(pc.getId());
                    CharacterClass liveClass = repos.classes().getById(cls.getId());
                    if (draft == null || liveClass == null || !Objects.equals(draft.getClassId(), cls.getId())
                            || !mapper.valueToTree(cls.getUpgradeTree()).equals(mapper.valueToTree(liveClass.getUpgradeTree()))) {
                        throw new IllegalArgumentException("This character or class changed. Reopen the upgrade tree before saving.");
                    }
                    UpgradeRules.unlock(draft, liveClass, n.getId(), repos.spells()::getById);
                    repos.players().save(draft);
                    pc.setUpgrades(draft.getUpgrades()); pc.setStats(draft.getStats()); pc.setSpells(draft.getSpells());
                    pc.setMaxHitPoints(draft.getMaxHitPoints()); pc.setCurrentHitPoints(draft.getCurrentHitPoints());
                    pc.setMaxMana(draft.getMaxMana()); pc.setCurrentMana(draft.getCurrentMana());
                    refresh[0].run(); changed.run();
                } catch (RuntimeException ex) { error(owner, ex.getMessage()); }
            });
            VBox graphPanel = graphPanel(graph);
            VBox.setVgrow(graphPanel, Priority.ALWAYS);
            content.getChildren().setAll(budget, note, graphPanel);
            if (cls.getUpgradeTree().getNodes().isEmpty()) {
                content.getChildren().add(owner.body("The GM can add branches and nodes in Worldbuilding > Class Upgrade Trees."));
            }
        };
        refresh[0].run();
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    static String rewardText(UpgradeTree.Reward r) {
        List<String> parts = new ArrayList<>();
        if (r.getHitPoints() != 0) parts.add("HP +" + r.getHitPoints());
        if (r.getMana() != 0) parts.add("Mana +" + r.getMana());
        if (r.getAbilityIncrease() != 0) parts.add(r.getAbilityId() + " +" + r.getAbilityIncrease());
        if (r.getArmorClass() != 0) parts.add("AC +" + r.getArmorClass());
        if (r.getAttackBonus() != 0) parts.add(UiTranslator.text("Attack bonus") + " +" + r.getAttackBonus());
        if (r.getBasicAttackDamage() != 0) parts.add(UiTranslator.text("Basic attack damage") + " +" + r.getBasicAttackDamage());
        if (r.getRageDamage() != 0) parts.add(UiTranslator.text("Rage damage") + " +" + r.getRageDamage());
        if (r.getSpeed() != 0) parts.add(UiTranslator.text("Speed") + " +" + r.getSpeed());
        if (!r.getSpellIds().isEmpty()) parts.add(UiTranslator.text("Spells / abilities") + ": " + String.join(", ", r.getSpellIds()));
        for (var feature : r.getFeatures()) parts.add(feature.getName() + ": " + feature.getDescription());
        return parts.isEmpty() ? UiTranslator.text("Narrative feat / existing training") : String.join("\n", parts);
    }

    private static Dialog<ButtonType> dialog(BaseScene owner, String title) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title); dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        owner.styleDialog(dialog); return dialog;
    }
    private static ScrollPane scroll(javafx.scene.Node node) { return new ScrollPane(node); }
    private static VBox graphPanel(UpgradeTreeView graph) {
        Slider zoom = new Slider(0.35, 1.25, Math.min(1, 900 / graph.getPrefWidth()));
        graph.scaleXProperty().bind(zoom.valueProperty());
        graph.scaleYProperty().bind(zoom.valueProperty());
        ScrollPane scroll = new ScrollPane(new javafx.scene.Group(graph));
        scroll.setPannable(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label label = new Label(UiTranslator.text("Zoom"));
        return new VBox(6, new HBox(8, label, zoom), scroll);
    }
    private static void error(BaseScene owner, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message); owner.styleDialog(alert); alert.showAndWait();
    }
}
