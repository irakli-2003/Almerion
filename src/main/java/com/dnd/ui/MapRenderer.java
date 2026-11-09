package com.dnd.ui;

import com.dnd.model.world.map.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws a {@link GameMap} onto a {@link Canvas}.
 *
 * <p>Extracted so that the plain map view and the battle map render identically; a fight
 * that looked different from the map the DM prepared would be actively confusing. Battle
 * specific decoration (health bars, turn highlight, spell ranges, death markers) is layered
 * on top through {@link Decorations}, which the plain view simply leaves null.</p>
 */
public class MapRenderer {

    public static final double DEFAULT_CELL_SIZE = 48.0;

    private final Path campaignRoot;
    private double cellSize = DEFAULT_CELL_SIZE;

    public MapRenderer(Path campaignRoot) {
        this.campaignRoot = campaignRoot;
    }

    public double getCellSize() {
        return cellSize;
    }

    public void setCellSize(double cellSize) {
        this.cellSize = Math.max(12, Math.min(160, cellSize));
    }

    /** Battle-only extras drawn over the base map. All fields are optional. */
    public static class Decorations {
        public MapObject selected;
        public MapObject currentTurn;
        /** Token whose ability/spell range is being previewed, or null. */
        public MapObject rangeOrigin;
        /** Range radius in grid cells; only used when {@link #rangeOrigin} is set. */
        public double rangeCells;
        /** Area-of-effect radius in cells drawn at the range edge, or 0 for none. */
        public double rangeRadiusCells;
        public boolean showHealthBars = true;
        /**
         * Movement costs for the selected creature, as produced by
         * {@code MovementCalculator.reachableFrom}, or null when no range is being shown.
         */
        public int[][] reachable;
    }

    public void render(Canvas canvas, GameMap map, Decorations decorations) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (map == null) return;

        drawLayers(gc, map);
        drawTerrain(gc, map);
        drawGrid(gc, map);
        drawImpassable(gc, map);
        drawDrawings(gc, map);
        if (decorations != null && decorations.reachable != null) drawReachable(gc, map, decorations);
        if (decorations != null && decorations.rangeOrigin != null) drawRange(gc, decorations);
        drawTokens(gc, map, decorations);
        drawWorldMarkers(gc, map);
    }

    /** Pins for squares that lead to other maps, and the party marker, over everything else. */
    private void drawWorldMarkers(GraphicsContext gc, GameMap map) {
        double s = cellSize;
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFont(Font.font("System", FontWeight.BOLD, Math.max(10, s * 0.24)));
        for (MapPin link : map.getMapLinks()) {
            if (link.getX() >= map.getWidth() || link.getY() >= map.getHeight()) continue;
            double cx = link.getX() * s + s / 2;
            double cy = link.getY() * s + s / 2;
            double r = s * 0.22;
            gc.setFill(Color.web("#c9a84c"));
            gc.setStroke(Color.web("#1a1a2e"));
            gc.setLineWidth(2);
            gc.fillPolygon(new double[]{cx, cx + r, cx, cx - r}, new double[]{cy - r * 1.4, cy, cy + r * 1.4, cy}, 4);
            gc.strokePolygon(new double[]{cx, cx + r, cx, cx - r}, new double[]{cy - r * 1.4, cy, cy + r * 1.4, cy}, 4);
            String name = link.getName() == null ? "" : link.getName();
            if (!name.isEmpty()) {
                double ty = link.getY() * s + s + Math.max(10, s * 0.24);
                gc.setStroke(Color.web("#000000cc"));
                gc.setLineWidth(3);
                gc.strokeText(name, cx, ty);
                gc.setFill(Color.web("#f3e3b0"));
                gc.fillText(name, cx, ty);
            }
        }
        Position party = map.getPartyPosition();
        if (party != null && party.getX() < map.getWidth() && party.getY() < map.getHeight()) {
            double x = party.getX() * s;
            double y = party.getY() * s;
            double pad = s * 0.12;
            gc.setFill(Color.web("#b3261ecc"));
            gc.fillOval(x + pad, y + pad, s - 2 * pad, s - 2 * pad);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(2.5);
            gc.strokeOval(x + pad, y + pad, s - 2 * pad, s - 2 * pad);
            // A small flag: pole and pennant.
            double poleX = x + s * 0.42;
            gc.strokeLine(poleX, y + s * 0.25, poleX, y + s * 0.75);
            gc.setFill(Color.WHITE);
            gc.fillPolygon(new double[]{poleX, poleX + s * 0.28, poleX},
                new double[]{y + s * 0.25, y + s * 0.36, y + s * 0.47}, 3);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    // ── Base map ────────────────────────────────────────────────────────────

    private void drawLayers(GraphicsContext gc, GameMap map) {
        if (map.getLayers() == null) return;
        List<MapLayer> sorted = map.getLayers().stream()
            .sorted(Comparator.comparingInt(MapLayer::getZOrder))
            .toList();
        for (MapLayer layer : sorted) {
            double lx = layer.getX() * cellSize, ly = layer.getY() * cellSize;
            double lw = layer.getWidth() * cellSize, lh = layer.getHeight() * cellSize;
            boolean drewImage = false;
            gc.save();
            gc.setGlobalAlpha(layer.getOpacity());
            if (layer.getImagePath() != null && campaignRoot != null) {
                Image img = ImageStore.load(campaignRoot, layer.getImagePath());
                if (img != null) {
                    gc.drawImage(img, lx, ly, lw, lh);
                    drewImage = true;
                }
            }
            if (!drewImage && layer.getFillColor() != null) {
                try {
                    gc.setFill(Color.web(layer.getFillColor()));
                    gc.fillRect(lx, ly, lw, lh);
                } catch (IllegalArgumentException ignored) {
                    // A layer with a malformed colour simply isn't painted rather than
                    // aborting the whole render.
                }
            }
            gc.restore();
        }
    }

    private void drawGrid(GraphicsContext gc, GameMap map) {
        gc.setStroke(Color.web("#ffffff22"));
        gc.setLineWidth(0.5);
        for (int x = 0; x <= map.getWidth(); x++) {
            gc.strokeLine(x * cellSize, 0, x * cellSize, map.getHeight() * cellSize);
        }
        for (int y = 0; y <= map.getHeight(); y++) {
            gc.strokeLine(0, y * cellSize, map.getWidth() * cellSize, y * cellSize);
        }
    }

    private void drawImpassable(GraphicsContext gc, GameMap map) {
        gc.setFill(Color.web("#000000cc"));
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                if (!map.getCell(x, y).isPassable()) {
                    gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
                }
            }
        }
    }

    /**
     * Tints squares by terrain so the DM can see at a glance where the ground is slow,
     * wet or vertical. Drawn under the grid lines and kept translucent so any background
     * image or colour layer still reads through.
     */
    private void drawTerrain(GraphicsContext gc, GameMap map) {
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                TerrainType terrain = map.getCell(x, y).getTerrain();
                if (terrain == null || terrain == TerrainType.NORMAL) continue;
                gc.setFill(Color.web(terrainColor(terrain)));
                gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
            }
        }
    }

    private String terrainColor(TerrainType terrain) {
        switch (terrain) {
            case WATER: return "#2a6fb055";
            case CLIMB: return "#8a5a2b55";
            case DIFFICULT: return "#6b5f2a55";
            default: return "#00000000";
        }
    }

    /**
     * Shades every square the selected creature can still reach this turn, so the DM can
     * see its remaining movement rather than counting squares by hand.
     */
    private void drawReachable(GraphicsContext gc, GameMap map, Decorations decorations) {
        int[][] costs = decorations.reachable;
        gc.setFill(Color.web("#4fc3f733"));
        gc.setStroke(Color.web("#4fc3f788"));
        gc.setLineWidth(1);
        for (int y = 0; y < map.getHeight() && y < costs.length; y++) {
            for (int x = 0; x < map.getWidth() && x < costs[y].length; x++) {
                if (costs[y][x] <= 0) continue;
                gc.fillRect(x * cellSize, y * cellSize, cellSize, cellSize);
                gc.strokeRect(x * cellSize + 0.5, y * cellSize + 0.5, cellSize - 1, cellSize - 1);
            }
        }
    }

    private void drawDrawings(GraphicsContext gc, GameMap map) {
        if (map.getDrawings() == null) return;
        List<Drawing> sorted = new ArrayList<>(map.getDrawings());
        sorted.sort(Comparator.comparingInt(Drawing::getZOrder));
        for (Drawing d : sorted) drawDrawing(gc, d);
    }

    private void drawDrawing(GraphicsContext gc, Drawing d) {
        List<Double> gp = d.getPoints();
        if (gp == null || gp.size() < 2) return;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < gp.size(); i += 2) {
            pts.add(new double[]{gp.get(i) * cellSize, gp.get(i + 1) * cellSize});
        }
        Color color;
        try {
            color = Color.web(d.getColor());
        } catch (Exception ex) {
            color = Color.web("#c9a84c");
        }
        gc.setStroke(color);
        gc.setFill(color);
        gc.setLineWidth(d.getLineWidth());
        gc.save();
        gc.setGlobalAlpha(d.getOpacity());
        switch (d.getType()) {
            case FREEHAND -> {
                for (int i = 1; i < pts.size(); i++) {
                    gc.strokeLine(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
                }
            }
            case LINE -> {
                double[] a = pts.get(0), b = pts.get(pts.size() - 1);
                gc.strokeLine(a[0], a[1], b[0], b[1]);
            }
            case RECTANGLE -> {
                double[] box = box(pts);
                if (d.isFilled()) gc.fillRect(box[0], box[1], box[2], box[3]);
                else gc.strokeRect(box[0], box[1], box[2], box[3]);
            }
            case OVAL -> {
                double[] box = box(pts);
                if (d.isFilled()) gc.fillOval(box[0], box[1], box[2], box[3]);
                else gc.strokeOval(box[0], box[1], box[2], box[3]);
            }
        }
        gc.restore();
    }

    private double[] box(List<double[]> pts) {
        double[] a = pts.get(0), b = pts.get(pts.size() - 1);
        return new double[]{
            Math.min(a[0], b[0]), Math.min(a[1], b[1]),
            Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])
        };
    }

    // ── Battle decoration ───────────────────────────────────────────────────

    /**
     * Draws the reach of the hovered spell or ability as a translucent disc, plus a
     * highlight on every creature standing inside it, so the DM can see valid targets
     * without measuring by eye.
     */
    private void drawRange(GraphicsContext gc, Decorations decorations) {
        Position origin = decorations.rangeOrigin.getPosition();
        if (origin == null || decorations.rangeCells <= 0) return;
        double cx = (origin.getX() + 0.5) * cellSize;
        double cy = (origin.getY() + 0.5) * cellSize;
        double r = decorations.rangeCells * cellSize;

        gc.setFill(Color.web("#c9a84c22"));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        gc.setStroke(Color.web("#c9a84ccc"));
        gc.setLineWidth(2);
        gc.setLineDashes(8, 6);
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        gc.setLineDashes();

        if (decorations.rangeRadiusCells > 0) {
            double ar = decorations.rangeRadiusCells * cellSize;
            gc.setStroke(Color.web("#e8c46a88"));
            gc.setLineWidth(1.5);
            gc.strokeOval(cx - ar, cy - ar, ar * 2, ar * 2);
        }
    }

    private void drawTokens(GraphicsContext gc, GameMap map, Decorations decorations) {
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                GridCell cell = map.getCell(x, y);
                int slot = 0;
                for (MapObject obj : cell.getOccupants()) {
                    drawToken(gc, obj, x, y, slot, decorations);
                    slot++;
                }
            }
        }
    }

    private void drawToken(GraphicsContext gc, MapObject obj, int cx, int cy, int slot, Decorations dec) {
        double inset = 4;
        double bx = cx * cellSize + inset + slot * 6;
        double by = cy * cellSize + inset + slot * 6;
        double size = cellSize - inset * 2 - slot * 6;
        if (size < 8) return;

        Color tokenColor = colorFor(obj);
        String imgPath = TokenSupport.imagePathOf(obj);

        boolean drewImage = false;
        if (imgPath != null && campaignRoot != null) {
            Image img = ImageStore.load(campaignRoot, imgPath);
            if (img != null) {
                gc.save();
                gc.beginPath();
                gc.arc(bx + size / 2, by + size / 2, size / 2, size / 2, 0, 360);
                gc.clip();
                gc.drawImage(img, bx, by, size, size);
                gc.restore();
                drewImage = true;
            }
        }
        if (!drewImage) {
            gc.setFill(tokenColor.deriveColor(0, 1, 0.5, 0.85));
            gc.fillOval(bx, by, size, size);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("DejaVu Serif", FontWeight.BOLD, size * 0.4));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(javafx.geometry.VPos.CENTER);
            gc.fillText(abbrev(TokenSupport.nameOf(obj)), bx + size / 2, by + size / 2);
        }

        gc.setStroke(tokenColor);
        gc.setLineWidth(1.5);
        gc.strokeOval(bx, by, size, size);

        if (dec == null) return;

        if (obj == dec.currentTurn) {
            gc.setStroke(Color.web("#e8c46a"));
            gc.setLineWidth(3);
            gc.strokeOval(bx - 3, by - 3, size + 6, size + 6);
        }
        if (obj == dec.selected) {
            gc.setStroke(Color.web("#ffffff"));
            gc.setLineWidth(1.5);
            gc.setLineDashes(4, 4);
            gc.strokeRect(cx * cellSize + 1, cy * cellSize + 1, cellSize - 2, cellSize - 2);
            gc.setLineDashes();
        }
        if (!TokenSupport.isCreature(obj)) return;

        if (dec.rangeOrigin != null && obj != dec.rangeOrigin && inRange(obj, dec)) {
            // Marking who can actually be hit is the point of the range preview; the circle
            // alone still leaves the DM squinting at partially covered squares.
            gc.setStroke(Color.web("#7ef7a0"));
            gc.setLineWidth(2.5);
            gc.strokeOval(bx - 2, by - 2, size + 4, size + 4);
        }

        CombatState state = TokenSupport.combatOf(obj);
        if (dec.showHealthBars && state.getMaxHitPoints() > 0) {
            drawHealthBar(gc, cx, cy, state);
        }
        if (state.isDead()) drawDeathCross(gc, bx, by, size);
        else if (state.isDowned()) drawDeathSaveCount(gc, bx, by, size, state.remainingDeathSaves());
        if (!state.getActiveEffects().isEmpty()) drawEffectPips(gc, cx, cy, state);
    }

    /**
     * A row of small violet pips along the top of the square, one per lingering effect.
     *
     * <p>Effects are the easiest thing in a fight to forget about, and the DM should not
     * have to click each token to remember who is still burning or frozen. The count is
     * capped so a heavily-afflicted creature does not overflow its square; the detail panel
     * remains the place to read what the effects actually are.</p>
     */
    private void drawEffectPips(GraphicsContext gc, int cx, int cy, CombatState state) {
        int count = Math.min(state.getActiveEffects().size(), 5);
        double pip = Math.max(4, cellSize * 0.11);
        double y = cy * cellSize + 3;
        for (int i = 0; i < count; i++) {
            double x = cx * cellSize + 4 + i * (pip + 2);
            gc.setFill(Color.web("#000000aa"));
            gc.fillOval(x - 1, y - 1, pip + 2, pip + 2);
            gc.setFill(Color.web("#b07de8"));
            gc.fillOval(x, y, pip, pip);
        }
    }

    private void drawHealthBar(GraphicsContext gc, int cx, int cy, CombatState state) {
        double barW = cellSize - 8;
        double barH = Math.max(3, cellSize * 0.09);
        double bx = cx * cellSize + 4;
        double by = (cy + 1) * cellSize - barH - 3;

        gc.setFill(Color.web("#000000aa"));
        gc.fillRect(bx, by, barW, barH);
        double fraction = state.healthFraction();
        // Green while healthy, amber when bloodied, red when nearly down: the DM should be
        // able to read the state of a fight at a glance without opening any panel.
        Color health = fraction > 0.5 ? Color.web("#4caf50") : fraction > 0.25 ? Color.web("#d9a441") : Color.web("#c0392b");
        gc.setFill(health);
        gc.fillRect(bx, by, barW * fraction, barH);

        if (state.getMaxMana() > 0) {
            double my = by - barH - 1;
            gc.setFill(Color.web("#000000aa"));
            gc.fillRect(bx, my, barW, barH);
            gc.setFill(Color.web("#4a7ad9"));
            gc.fillRect(bx, my, barW * state.manaFraction(), barH);
        }
    }

    private void drawDeathCross(GraphicsContext gc, double bx, double by, double size) {
        gc.setStroke(Color.web("#e01b1b"));
        gc.setLineWidth(Math.max(3, size * 0.12));
        gc.strokeLine(bx + size * 0.2, by + size * 0.2, bx + size * 0.8, by + size * 0.8);
        gc.strokeLine(bx + size * 0.8, by + size * 0.2, bx + size * 0.2, by + size * 0.8);
    }

    private void drawDeathSaveCount(GraphicsContext gc, double bx, double by, double size, int remaining) {
        gc.setFill(Color.web("#000000bb"));
        gc.fillOval(bx + size * 0.18, by + size * 0.18, size * 0.64, size * 0.64);
        gc.setFill(Color.web("#e01b1b"));
        gc.setFont(Font.font("DejaVu Serif", FontWeight.BOLD, size * 0.5));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText(String.valueOf(remaining), bx + size / 2, by + size / 2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    public static Color colorFor(MapObject obj) {
        if (obj instanceof PlayerToken) return Color.web("#4466aa");
        if (obj instanceof NpcToken) return Color.web("#66aa44");
        if (obj instanceof MonsterToken) return Color.web("#aa4444");
        if (obj instanceof BeastToken) return Color.web("#aa7744");
        return Color.web("#aaaa44");
    }

    public static String abbrev(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return String.valueOf(parts[0].charAt(0)).toUpperCase() + String.valueOf(parts[1].charAt(0)).toUpperCase();
    }

    /** Chebyshev distance in cells, the usual grid reckoning where diagonals cost one square. */
    public static double gridDistance(Position a, Position b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private static boolean inRange(MapObject token, Decorations dec) {
        if (dec.rangeCells <= 0) return false;
        return gridDistance(dec.rangeOrigin.getPosition(), token.getPosition()) <= dec.rangeCells;
    }
}
