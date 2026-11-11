package com.dnd.ui.scenes;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.JsonMappers;
import com.dnd.model.character.*;
import com.dnd.model.magic.Spell;
import com.dnd.ui.ImageStore;
import com.dnd.ui.components.EntityForm;
import com.dnd.ui.components.ImageFolders;
import com.dnd.ui.i18n.UiTranslator;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.util.*;

public final class UpgradeTreeWindow {
    private static final String ZOOM = "almerion.upgrade-zoom", ZOOM_HANDLER = "almerion.upgrade-zoom-handler";
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
        Runnable refresh = () -> refreshGraph(graph, dialog.getDialogPane(), owner, repos, draft);
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
            n.setParentId(UpgradeRules.ROOT);
            if (editNode(owner, repos, draft, n, true)) refresh.run();
        });
        Label hint = owner.body("Each level-up earns one choice. The parent and all additional prerequisites are required; branches may mix."
            + " Minimum level follows the node's tier: foundation 1, then 2, 3, 4 and so on."
            + " Exclusive links block both directions. IDs stay stable when renamed."
            + " Spell rewards can grant any GM-created ability from Worldbuilding > Spells."
            + " Every node grants a named feat or ability as well as any numeric rewards."
            + " GM-resolved feats state their action, limits and effect; granted spells use the spell system."
            + " Saved unlocks keep their original rewards when the tree changes.");
        hint.setWrapText(true);
        Label controls = owner.body("Branches are vertical sections. Ctrl+ / Ctrl- zoom; drag to pan.");
        VBox content = new VBox(10, hint, controls, new FlowPane(8, 8, branch, editBranch, deleteBranch, add), graph, message);
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

    private static void refreshGraph(BorderPane graph, DialogPane dialogPane, BaseScene owner, CampaignRepositories repos, CharacterClass cls) {
        graph.setCenter(graphPanel(new UpgradeTreeView(repos.campaignRoot(), cls, null, n -> {
            if (UpgradeRules.ROOT.equals(n.getId())) { owner.showInfo(n.getName(), n.getDescription()); return; }
            if (editNode(owner, repos, cls, n, false)) refreshGraph(graph, dialogPane, owner, repos, cls);
        }), dialogPane));
    }

    private static boolean editNode(BaseScene owner, CampaignRepositories repos, CharacterClass cls,
                                     UpgradeTree.Node original, boolean isNew) {
        var mapper = JsonMappers.forCampaign(repos.campaignRoot());
        var draft = mapper.convertValue(original, UpgradeTree.Node.class);
        var tree = cls.getUpgradeTree();
        var catalogs = new LinkedHashMap<>(EntityDetailScene.catalogsFor(repos));
        catalogs.put("branchId", tree.getBranches().stream().map(b -> new EntityForm.Ref(b.getId(), b.getName())).toList());
        var links = tree.getNodes().stream().filter(n -> !n.getId().equals(draft.getId()))
            .map(n -> new EntityForm.Ref(n.getId(), n.getName())).toList();
        var parents = new ArrayList<>(links);
        parents.addFirst(new EntityForm.Ref(UpgradeRules.ROOT, UpgradeRules.foundation(cls, null).getName()));
        catalogs.put("parentId", parents); catalogs.put("children", links);
        catalogs.put("prerequisites", links); catalogs.put("exclusiveWith", links);
        var spellRefs = repos.spells().list().stream().map(s -> new EntityForm.Ref(s.getId(), s.getName())).toList();
        catalogs.put("spellIds", spellRefs); catalogs.put("spellId", spellRefs);
        catalogs.put("alternativeSpellIds", spellRefs);
        List<EntityForm.Ref> abilities = new ArrayList<>();
        abilities.add(new EntityForm.Ref("", "(none)"));
        repos.statSchema().getAbilities().forEach(a -> abilities.add(new EntityForm.Ref(a.id(), a.name())));
        catalogs.put("abilityId", abilities);
        EntityForm form = EntityForm.of(UpgradeTree.Node.class, draft, Set.of("id", "imagePath", "minimumLevel"), catalogs);
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
        Label tier = owner.body("Minimum level follows the parent hierarchy. Changing a parent automatically updates the node and its descendants.");
        tier.setWrapText(true);
        VBox content = new VBox(10, tier, new FlowPane(8, 8, image, clear), picture, form.getNode(), message);
        ScrollPane scroll = scroll(content); scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(850); scroll.setPrefViewportHeight(600);
        dialog.getDialogPane().setContent(scroll);
        boolean[] saved = {false};
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                form.writeTo(draft);
                var candidate = mapper.convertValue(tree, UpgradeTree.class);
                UpgradeRules.editConnections(candidate, mapper.convertValue(draft, UpgradeTree.Node.class));
                validateReward(repos, draft, spellCatalog(repos));
                cls.setUpgradeTree(candidate);
                saved[0] = true;
            } catch (RuntimeException ex) { message.setText(ex.getMessage()); event.consume(); }
        });
        if (!isNew) dialog.getDialogPane().lookupButton(delete).addEventFilter(ActionEvent.ACTION, event -> {
            if (tree.getNodes().stream().anyMatch(n -> UpgradeRules.requiredNodes(n).contains(original.getId())
                    || n.getExclusiveWith().contains(original.getId()))) {
                message.setText(UiTranslator.text("Remove links to this node before deleting it."));
                event.consume(); return;
            }
            var candidate = mapper.convertValue(tree, UpgradeTree.class);
            candidate.getNodes().removeIf(n -> n.getId().equals(original.getId()));
            UpgradeRules.validate(candidate);
            cls.setUpgradeTree(candidate); saved[0] = true;
        });
        dialog.showAndWait();
        return saved[0];
    }

    private static void validateRewards(CampaignRepositories repos, UpgradeTree tree) {
        var spells = spellCatalog(repos);
        for (var n : tree.getNodes()) {
            validateReward(repos, n, spells);
        }
    }

    private static void validateReward(CampaignRepositories repos, UpgradeTree.Node n, Map<String, Spell> spells) {
        if (n.getReward().getFeatures().isEmpty() && n.getReward().getSpellIds().isEmpty()) {
            throw new IllegalArgumentException("Every upgrade node must grant a named feat or ability: " + n.getName());
        }
        UpgradeRules.validateSpells(n.getReward(), spells::get);
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
                    var selections = chooseSpellRewards(owner, pc, n.getReward(), spellCatalog(repos));
                    if (selections.isEmpty()) return;
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
                    UpgradeRules.unlock(draft, liveClass, n.getId(), spellCatalog(repos)::get, selections.get());
                    repos.players().save(draft);
                    pc.setUpgrades(draft.getUpgrades()); pc.setStats(draft.getStats()); pc.setSpells(draft.getSpells());
                    pc.setMaxHitPoints(draft.getMaxHitPoints()); pc.setCurrentHitPoints(draft.getCurrentHitPoints());
                    pc.setMaxMana(draft.getMaxMana()); pc.setCurrentMana(draft.getCurrentMana());
                    refresh[0].run(); changed.run();
                } catch (RuntimeException ex) { error(owner, ex.getMessage()); }
            });
            VBox graphPanel = graphPanel(graph, dialog.getDialogPane());
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

    private static Map<String, Spell> spellCatalog(CampaignRepositories repos) {
        Map<String, Spell> spells = new LinkedHashMap<>();
        repos.spells().list().forEach(s -> spells.put(s.getId(), s));
        return spells;
    }

    static Optional<Map<String, String>> chooseSpellRewards(BaseScene owner, PlayerCharacter pc,
            UpgradeTree.Reward reward, Map<String, Spell> spells) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (var choice : UpgradeRules.spellChoices(pc, reward, spells::get)) {
            if (choice.alternatives().isEmpty()) {
                Alert warning = new Alert(Alert.AlertType.WARNING,
                    "No unlearned predefined alternatives are available. No new spell will be learned; this unlock still grants its feat and other bonuses. Continue?",
                    ButtonType.OK, ButtonType.CANCEL);
                warning.setTitle("Known Spell Reward"); warning.setHeaderText(choice.primary().getName());
                owner.styleDialog(warning);
                if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return Optional.empty();
                selected.put(choice.primary().getId(), choice.primary().getId());
            } else {
                var options = choice.alternatives().stream().filter(s -> !selected.containsValue(s.getId()))
                    .map(s -> new EntityForm.Ref(s.getId(), s.getName())).toList();
                if (options.isEmpty()) throw new IllegalArgumentException("The selected rewards overlap. Choose different predefined alternatives.");
                ChoiceDialog<EntityForm.Ref> pick = new ChoiceDialog<>(options.getFirst(), options);
                pick.setTitle("Alternative Spell Reward");
                pick.setHeaderText(choice.primary().getName());
                pick.setContentText("Choose one predefined spell of the same spell level.");
                owner.styleDialog(pick);
                var picked = pick.showAndWait();
                if (picked.isEmpty()) return Optional.empty();
                selected.put(choice.primary().getId(), picked.get().id());
            }
        }
        return Optional.of(selected);
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
        for (var alternatives : r.getSpellAlternatives()) parts.add(UiTranslator.text("Predefined same-level alternatives")
            + " (" + alternatives.getSpellId() + "): " + String.join(", ", alternatives.getAlternativeSpellIds()));
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
    static VBox graphPanel(UpgradeTreeView graph, DialogPane dialogPane) {
        double initial = dialogPane.getProperties().get(ZOOM) instanceof Slider previous
            ? previous.getValue() : Math.max(0.35, Math.min(1, 900 / graph.getPrefWidth()));
        Slider zoom = new Slider(0.35, 1.25, initial);
        zoom.setId("upgrade-tree-zoom");
        dialogPane.getProperties().put(ZOOM, zoom);
        if (dialogPane.getProperties().putIfAbsent(ZOOM_HANDLER, true) == null) {
            dialogPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (!event.isControlDown() || event.isAltDown()) return;
                KeyCode code = event.getCode();
                boolean increase = code == KeyCode.PLUS || code == KeyCode.EQUALS || code == KeyCode.ADD;
                boolean decrease = code == KeyCode.MINUS || code == KeyCode.SUBTRACT;
                if (!increase && !decrease) return;
                Slider active = (Slider)dialogPane.getProperties().get(ZOOM);
                active.setValue(Math.max(active.getMin(), Math.min(active.getMax(), active.getValue() + (increase ? 0.1 : -0.1))));
                event.consume();
            });
        }
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
