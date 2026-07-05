package soundspace;

import javafx.scene.paint.Color;

/**
 * Central place for window sizing, gameplay dimensions and the colour palette.
 * Keeping these in one spot makes it easy to re-theme the whole game.
 */
public final class Config {

    private Config() { } // utility class – never instantiated

    // ---- Window / play-field geometry -------------------------------------
    public static final int WIDTH = 1400;
    public static final int HEIGHT = 800;

    /** Side length (in 3D local units) of the central play square. */
    public static final double SQUARE_SIZE = 200;

    /** Side length of the player's cursor. */
    public static final double CURSOR_SIZE = SQUARE_SIZE / 10;

    /** How long a note takes to fly from the far plane to the hit plane. */
    public static final double NOTE_TRAVEL_SECONDS = 2.0;

    /** Starting/maximum health (number of misses tolerated before failure). */
    public static final int MAX_HEALTH = 12;

    /** Base points awarded for a single hit (before the combo multiplier). */
    public static final int BASE_HIT_POINTS = 100;

    /** Highest combo multiplier the score ring will display. */
    public static final int MAX_MULTIPLIER = 8;

    // ---- Colour palette (Sound-Space inspired synthwave look) -------------
    public static final Color BACKGROUND      = Color.web("#05070f");
    public static final Color GRID_LINE       = Color.web("#17c3d6");
    public static final Color GRID_LINE_FADED = Color.web("#17c3d6", 0.18);
    public static final Color ACCENT          = Color.web("#22d3ee");

    public static final Color NOTE_CYAN = Color.web("#22d3ee");
    public static final Color NOTE_PINK = Color.web("#f43f6e");

    public static final Color CURSOR_COLOR = Color.web("#f5f7ff");

    public static final Color HEALTH_GOOD = Color.web("#8ef04a");
    public static final Color HEALTH_WARN = Color.web("#f5d442");
    public static final Color HEALTH_BAD  = Color.web("#f4564a");

    public static final Color TEXT_PRIMARY = Color.web("#ffffff");
    public static final Color TEXT_DIM     = Color.web("#8fa3b8");
}
