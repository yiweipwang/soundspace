package soundspace;

/**
 * Mutable state for a single play-through.  Reset between songs.
 */
public class GameState {

    private int combo;
    private int maxCombo;
    private int totalNotes;
    private int successfulHits;
    private int health;
    private int score;
    private boolean failed;

    public GameState() {
        reset();
    }

    /** Call before every new song to zero-out everything. */
    public void reset() {
        combo      = 0;
        maxCombo   = 0;
        totalNotes = 0;
        successfulHits = 0;
        health     = Config.MAX_HEALTH;
        score      = 0;
        failed     = false;
    }

    // ---- hit / miss -------------------------------------------------------

    public void registerHit() {
        totalNotes++;
        successfulHits++;
        combo++;
        score += Config.BASE_HIT_POINTS * getMultiplier();
        if (combo > maxCombo) maxCombo = combo;
        if (health < Config.MAX_HEALTH) health++;
    }

    public void registerMiss() {
        totalNotes++;
        if (combo > maxCombo) maxCombo = combo;
        combo = 0;
        health--;
        if (health <= 0) {
            health = 0;
            failed = true;
        }
    }

    // ---- derived values ---------------------------------------------------

    public double getAccuracy() {
        return (totalNotes == 0) ? 100.0 : ((double) successfulHits / totalNotes) * 100;
    }

    /** Multiplier: 1 at combo < 5, up to MAX_MULTIPLIER at high combos. */
    public int getMultiplier() {
        if (combo < 5) return 1;
        if (combo < 15) return 2;
        if (combo < 30) return 4;
        return Config.MAX_MULTIPLIER;
    }

    /** Progress ring for the multiplier (0.0 – 1.0). */
    public double getMultiplierProgress() {
        if (combo < 5)  return combo / 5.0;
        if (combo < 15) return (combo - 5) / 10.0;
        if (combo < 30) return (combo - 15) / 15.0;
        return 1.0;
    }

    public String getLetterGrade() {
        double a = getAccuracy();
        if (a == 100.0) return "SSS";
        if (a >= 97) return "SS";
        if (a >= 95) return "S";
        if (a >= 90) return "A";
        if (a >= 80) return "B";
        if (a >= 70) return "C";
        if (a >= 60) return "D";
        return "F";
    }

    public javafx.scene.paint.Color getGradeColor() {
        double a = getAccuracy();
        if (a == 100.0) return javafx.scene.paint.Color.web("#d946ef");
        if (a >= 97)    return javafx.scene.paint.Color.GOLD;
        if (a >= 95)    return javafx.scene.paint.Color.SILVER;
        if (a >= 90)    return javafx.scene.paint.Color.web("#4ade80");
        if (a >= 80)    return javafx.scene.paint.Color.YELLOW;
        if (a >= 70)    return javafx.scene.paint.Color.ORANGE;
        if (a >= 60)    return javafx.scene.paint.Color.web("#ef4444");
        return javafx.scene.paint.Color.DARKRED;
    }

    public javafx.scene.paint.Color getAccuracyColor() {
        double a = getAccuracy();
        if (a >= 90) return javafx.scene.paint.Color.web("#4ade80");
        if (a >= 80) return javafx.scene.paint.Color.YELLOW;
        if (a >= 70) return javafx.scene.paint.Color.ORANGE;
        if (a >= 60) return javafx.scene.paint.Color.web("#ef4444");
        return javafx.scene.paint.Color.DARKRED;
    }

    /** Normalised health 0.0 – 1.0. */
    public double getHealthFraction() {
        return (double) health / Config.MAX_HEALTH;
    }

    public javafx.scene.paint.Color getHealthColor() {
        double h = getHealthFraction();
        if (h > 0.6)  return Config.HEALTH_GOOD;
        if (h > 0.3)  return Config.HEALTH_WARN;
        return Config.HEALTH_BAD;
    }

    // ---- plain getters ----------------------------------------------------

    public int getCombo()          { return combo; }
    public int getMaxCombo()       { return maxCombo; }
    public int getTotalNotes()     { return totalNotes; }
    public int getSuccessfulHits() { return successfulHits; }
    public int getHealth()         { return health; }
    public int getScore()          { return score; }
    public boolean isFailed()      { return failed; }
}
