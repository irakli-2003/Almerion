package com.dnd.ui.components;

import com.dnd.data.CampaignRepositories;
import com.dnd.data.EntityDescriptions;
import com.dnd.model.item.Item;
import com.dnd.model.magic.Spell;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Hover cards that show the full description of a spell or item wherever it is listed. */
public final class EntityHoverCard {

    private static final double MAX_WIDTH = 440;
    private static final String KEY = "dnd.hoverCard";

    private EntityHoverCard() {
    }

    public static void spell(Node node, Spell spell, CampaignRepositories repos) {
        if (node == null || spell == null || repos == null) return;
        EntityDescriptions d = EntityDescriptions.of(repos);
        install(node, "✦ " + spell.getName(), d.spellSummary(spell), d.spellBody(spell));
    }

    public static void item(Node node, Item item, CampaignRepositories repos) {
        if (node == null || item == null || repos == null) return;
        EntityDescriptions d = EntityDescriptions.of(repos);
        install(node, "⚒ " + item.getName(), d.itemSummary(item), d.itemBody(item));
    }

    /** Installs (or replaces) a card on any node. */
    public static void install(Node node, String title, String summary, String body) {
        Tooltip tip = tooltip(title, summary, body);
        Object old = node.getProperties().get(KEY);
        if (old instanceof Tooltip t) Tooltip.uninstall(node, t);
        if (node instanceof Control c) c.setTooltip(tip);
        else Tooltip.install(node, tip);
        node.getProperties().put(KEY, tip);
    }

    /**
     * A card sized to its content: lines wrap at {@link #MAX_WIDTH}, shorter text gives a
     * narrower card, and the height is exactly what the wrapped text needs.
     */
    public static Tooltip tooltip(String title, String summary, String body) {
        String family = Font.getDefault().getFamily();
        double size = Font.getDefault().getSize();
        VBox box = new VBox(4, text(title, Font.font(family, FontWeight.BOLD, 14), "#f4e4c1"));
        if (summary != null && !summary.isBlank()) {
            box.getChildren().add(text(summary, Font.font(family, FontWeight.NORMAL, FontPosture.ITALIC, size), "#d8c79a"));
        }
        if (body != null && !body.isBlank()) {
            box.getChildren().add(text(body, Font.font(family, size), "#eeeeee"));
        }
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: rgba(28,24,20,0.97); -fx-border-color: #8b6b3a;"
            + " -fx-border-width: 1; -fx-background-radius: 4; -fx-border-radius: 4;");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Tooltip tip = new Tooltip();
        tip.setGraphic(box);
        tip.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        tip.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-background-insets: 0;");
        tip.setShowDelay(Duration.millis(250));
        tip.setHideDelay(Duration.millis(120));
        tip.setShowDuration(Duration.INDEFINITE);
        return tip;
    }

    private static Text text(String s, Font font, String color) {
        Text t = new Text(s);
        t.setFont(font);
        t.setFill(Color.web(color));
        double natural = t.getLayoutBounds().getWidth();
        t.setWrappingWidth(Math.min(MAX_WIDTH, Math.ceil(natural) + 1));
        return t;
    }

    /**
     * Shows a card when the mouse rests on a spell or item name inside {@code area}'s text.
     * Names are looked up fresh on each hover, so newly created entries work immediately.
     */
    public static void installMentions(TextArea area, CampaignRepositories repos) {
        if (area == null || repos == null) return;
        Tooltip[] showing = new Tooltip[1];
        String[] shownKey = new String[1];
        NameCache cache = new NameCache(repos);
        area.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            Object hit = mentionUnder(area, e, cache.names());
            String key = hit instanceof Spell s ? "s:" + s.getId() : hit instanceof Item i ? "i:" + i.getId() : null;
            if (key != null && key.equals(shownKey[0]) && showing[0] != null && showing[0].isShowing()) return;
            hide(showing);
            shownKey[0] = key;
            if (hit == null) return;
            EntityDescriptions d = EntityDescriptions.of(repos);
            Tooltip tip = hit instanceof Spell s
                ? tooltip("✦ " + s.getName(), d.spellSummary(s), d.spellBody(s))
                : tooltip("⚒ " + ((Item) hit).getName(), d.itemSummary((Item) hit), d.itemBody((Item) hit));
            Window w = area.getScene() == null ? null : area.getScene().getWindow();
            if (w == null) return;
            tip.show(area, e.getScreenX() + 14, e.getScreenY() + 16);
            showing[0] = tip;
        });
        area.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            hide(showing);
            shownKey[0] = null;
        });
        area.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            hide(showing);
            shownKey[0] = null;
        });
    }

    private static void hide(Tooltip[] showing) {
        if (showing[0] != null) showing[0].hide();
        showing[0] = null;
    }

    /** Spell and item names by lower-case name, reloaded at most every few seconds. */
    private static final class NameCache {
        private final CampaignRepositories repos;
        private Map<String, Object> names = Map.of();
        private long loadedAt;

        NameCache(CampaignRepositories repos) {
            this.repos = repos;
        }

        Map<String, Object> names() {
            long now = System.currentTimeMillis();
            if (now - loadedAt > 5000) {
                Map<String, Object> fresh = new LinkedHashMap<>();
                for (Item i : repos.items().list()) put(fresh, i.getName(), i);
                for (Spell s : repos.spells().list()) put(fresh, s.getName(), s);
                names = fresh;
                loadedAt = now;
            }
            return names;
        }
    }

    private static Object mentionUnder(TextArea area, MouseEvent e, Map<String, Object> names) {
        if (!(area.getSkin() instanceof TextAreaSkin skin)) return null;
        Node content = area.lookup(".content");
        if (content == null) return null;
        javafx.geometry.Point2D p = content.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (p == null || p.getX() < 0 || p.getY() < 0
            || p.getX() > content.getLayoutBounds().getWidth() || p.getY() > content.getLayoutBounds().getHeight()) {
            return null;
        }
        int index;
        try {
            index = skin.getIndex(p.getX(), p.getY()).getCharIndex();
        } catch (RuntimeException ex) {
            return null;
        }
        return EntityDescriptions.mentionAt(area.getText(), index, names);
    }

    private static void put(Map<String, Object> names, String name, Object value) {
        if (name == null) return;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.length() >= 3) names.putIfAbsent(key, value);
    }
}
