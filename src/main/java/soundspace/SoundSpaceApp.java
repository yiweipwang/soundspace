package soundspace;

import javafx.animation.*;
import javafx.application.Application;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.FileChooser;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Glow;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.Stop;
import javafx.scene.robot.Robot;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Sound Space remake – JavaFX rhythm game.
 * <p>
 * Notes fly toward the player along the Z-axis inside a 3D perspective scene.
 * The player moves a cursor inside a central square to intersect the notes.
 */
public class SoundSpaceApp extends Application {

    // ---- state ------------------------------------------------------------
    private final GameState state = new GameState();

    private double mousePosX;
    private double mousePosY;
    private Robot  robot;
    private int    colorNum;

    // ---- scenes & stage ---------------------------------------------------
    private Stage       primaryStage;
    private Scene       menuScene;
    private Scene       gameScene;

    // ---- game-scene nodes -------------------------------------------------
    private Group       gameRoot;
    private Rectangle   border;
    private Rectangle   cursor;

    // HUD elements
    private Text   comboLabel;
    private Text   comboValue;
    private Text   scoreValue;
    private Text   missesLabel;
    private Text   missesValue;
    private Text   notesLabel;
    private Text   notesValue;

    private Text   multiplierText;
    private Arc    multiplierArc;

    private Text   accuracyText;
    private Text   timerText;

    private Rectangle healthBar;
    private Rectangle healthTrack;

    // ---- audio ------------------------------------------------------------
    private MediaPlayer mediaPlayer;
    private MediaPlayer menuMediaPlayer;
    private Timeline    gameTimeline;

    // ---- custom songs: name → audio file path -----------------------------
    private final java.util.Map<String, String> customSongPaths = new java.util.LinkedHashMap<>();
    private ComboBox<String> songBox;          // keep a reference so import can add items

    // ---- position helpers for the 3×3 note grid ---------------------------
    private final double[] cellPositions = {
            -Config.SQUARE_SIZE / 2,
            -Config.SQUARE_SIZE / 6,
             Config.SQUARE_SIZE / 6
    };

    // ---- track how many notes are in the current map ----------------------
    private int totalMapNotes;

    // -----------------------------------------------------------------------
    //  JavaFX lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("Sound Space");

        // Load previously imported custom songs
        loadSavedCustomSongs();

        buildMenuScene();
        buildGameScene();

        stage.setScene(menuScene);
        stage.show();
    }

    /** Load all .beats files from custom_songs/ and register them. */
    private void loadSavedCustomSongs() {
        for (CustomSongManager.CustomSong cs : CustomSongManager.loadAll()) {
            BeatMap.registerCustom(cs.name, cs.beats);
            customSongPaths.put(cs.name, cs.audioPath);
        }
    }

    // -----------------------------------------------------------------------
    //  Menu scene
    // -----------------------------------------------------------------------

    private void buildMenuScene() {
        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPrefSize(Config.WIDTH, Config.HEIGHT);
        root.setStyle("-fx-background-color: #05070f;");

        Text title = new Text("SOUND SPACE");
        title.setFont(Font.font("Consolas", FontWeight.BOLD, 54));
        title.setFill(Config.ACCENT);
        title.setEffect(new Glow(0.6));

        Text subtitle = new Text("Keithley's Remake");
        subtitle.setFont(Font.font("Consolas", 18));
        subtitle.setFill(Config.TEXT_DIM);

        // ---- song selector (built-in + any previously imported custom songs)
        songBox = styledCombo(Config.WIDTH / 3.0, Config.HEIGHT / 10.0);
        songBox.getItems().addAll("ThisLove", "Engineer", "Gangsta", "test");
        // Add any custom songs already loaded from disk
        for (String name : customSongPaths.keySet()) {
            if (!songBox.getItems().contains(name)) {
                songBox.getItems().add(name);
            }
        }
        songBox.setValue("ThisLove");

        ComboBox<String> diffBox = styledCombo(Config.WIDTH / 4.0, Config.HEIGHT / 10.0);
        diffBox.getItems().addAll("Easy", "Hard");
        diffBox.setValue("Easy");

        // ---- import button
        Button importBtn = new Button("＋  IMPORT SONG");
        importBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #22d3ee;" +
                "-fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 8 28;" +
                "-fx-border-color: #22d3ee; -fx-border-width: 1;" +
                "-fx-border-radius: 6; -fx-background-radius: 6; -fx-cursor: hand;");

        // Status label shown during import
        Text statusText = new Text("");
        statusText.setFont(Font.font("Consolas", 14));
        statusText.setFill(Config.TEXT_DIM);

        importBtn.setOnAction(e -> handleImport(statusText));

        // ---- start button
        Button startBtn = new Button("▶  START");
        startBtn.setStyle(
                "-fx-background-color: #22d3ee; -fx-text-fill: #05070f;" +
                "-fx-font-size: 22px; -fx-font-weight: bold; -fx-padding: 12 40;" +
                "-fx-background-radius: 6; -fx-cursor: hand;");
        startBtn.setOnAction(e -> launchGame(songBox.getValue(), diffBox.getValue()));

        root.getChildren().addAll(title, subtitle, songBox, diffBox, startBtn,
                importBtn, statusText);
        menuScene = new Scene(root, Config.WIDTH, Config.HEIGHT);
        menuScene.setFill(Config.BACKGROUND);

        // start menu music
        try {
            String menuMusicFile = "src/menu_full.mp3";
            Media menuSound = new Media(new File(menuMusicFile).toURI().toString());
            menuMediaPlayer = new MediaPlayer(menuSound);
            menuMediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
            menuMediaPlayer.play();
        } catch (Exception ignored) { /* allow running without audio files */ }
    }

    // -----------------------------------------------------------------------
    //  Custom song import
    // -----------------------------------------------------------------------

    private void handleImport(Text statusText) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select an audio file");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.mp3", "*.ogg", "*.flac"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        File chosen = fc.showOpenDialog(primaryStage);
        if (chosen == null) return;

        statusText.setText("Analysing beats…  (this may take a moment)");
        statusText.setFill(Config.ACCENT);

        // Run beat detection on a background thread to keep the UI responsive
        new Thread(() -> {
            try {
                CustomSongManager.CustomSong cs = CustomSongManager.importSong(chosen);

                // Back to the JavaFX thread to update UI
                javafx.application.Platform.runLater(() -> {
                    BeatMap.registerCustom(cs.name, cs.beats);
                    customSongPaths.put(cs.name, cs.audioPath);

                    if (!songBox.getItems().contains(cs.name)) {
                        songBox.getItems().add(cs.name);
                    }
                    songBox.setValue(cs.name);

                    statusText.setText("✓  Imported \"" + cs.name + "\"  (" +
                            cs.beats.length + " beats detected)");
                    statusText.setFill(Color.web("#4ade80"));
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() -> {
                    statusText.setText("✗  Import failed: " + ex.getMessage());
                    statusText.setFill(Config.HEALTH_BAD);
                });
                ex.printStackTrace();
            }
        }).start();
    }

    private ComboBox<String> styledCombo(double w, double h) {
        ComboBox<String> cb = new ComboBox<>();
        cb.setPrefWidth(w);
        cb.setPrefHeight(h);
        cb.setStyle(
                "-fx-background-color: #111827; -fx-text-fill: white;" +
                "-fx-font-size: 16px; -fx-border-color: #22d3ee;" +
                "-fx-border-width: 1; -fx-border-radius: 4;" +
                "-fx-background-radius: 4;");
        return cb;
    }

    // -----------------------------------------------------------------------
    //  Game scene  (3D perspective, Sound-Space style HUD)
    // -----------------------------------------------------------------------

    private void buildGameScene() {
        gameRoot = new Group();
        gameScene = new Scene(gameRoot, Config.WIDTH, Config.HEIGHT, true);
        gameScene.setFill(Config.BACKGROUND);

        PerspectiveCamera cam = new PerspectiveCamera(true);
        cam.getTransforms().add(new Translate(0, 0, -500));
        cam.setNearClip(1);
        cam.setFarClip(10000);
        gameScene.setCamera(cam);
        gameScene.setCursor(Cursor.NONE);

        // ---- central play border (corner brackets, not full rect) ---------
        double S = Config.SQUARE_SIZE;
        double half = S / 2;
        double tick = S * 0.18;          // bracket arm length
        double sw   = 2.5;              // stroke width

        border = new Rectangle(-half, -half, S, S);
        border.setFill(null);
        border.setStroke(Color.TRANSPARENT);  // invisible; brackets draw it
        gameRoot.getChildren().add(border);

        // draw 4 corner brackets
        for (int cx = -1; cx <= 1; cx += 2) {
            for (int cy = -1; cy <= 1; cy += 2) {
                double bx = cx * half;
                double by = cy * half;

                Line h = new Line(bx, by, bx - cx * tick, by);
                Line v = new Line(bx, by, bx, by - cy * tick);
                for (Line l : new Line[]{h, v}) {
                    l.setStroke(Config.ACCENT);
                    l.setStrokeWidth(sw);
                    l.setStrokeLineCap(StrokeLineCap.ROUND);
                }
                gameRoot.getChildren().addAll(h, v);
            }
        }

        // ---- cursor -------------------------------------------------------
        Pane cursorPane = new Pane();
        cursor = new Rectangle(Config.CURSOR_SIZE, Config.CURSOR_SIZE);
        cursor.setFill(null);
        cursor.setStroke(Config.CURSOR_COLOR);
        cursor.setStrokeWidth(3);
        cursor.setArcWidth(6);
        cursor.setArcHeight(6);
        cursor.setEffect(new DropShadow(BlurType.GAUSSIAN, Color.WHITE, 8, 0.4, 0, 0));
        cursorPane.getChildren().add(cursor);
        gameRoot.getChildren().add(cursorPane);

        // ---- HUD (2D overlay, fixed Z = 0) --------------------------------
        buildHud();

        // ---- mouse handling -----------------------------------------------
        robot = new Robot();

        gameScene.setOnMouseMoved(event -> {
            double x = event.getX() - Config.CURSOR_SIZE / 2;
            double y = event.getY() - Config.CURSOR_SIZE / 2;
            double minX = -half + 2;
            double minY = -half + 2;
            double maxX = minX + S - Config.CURSOR_SIZE - 4;
            double maxY = minY + S - Config.CURSOR_SIZE - 4;

            cursor.setX(Math.max(minX, Math.min(x, maxX)));
            cursor.setY(Math.max(minY, Math.min(y, maxY)));

            Point2D minScreen = border.localToScreen(minX, minY);
            Point2D maxScreen = border.localToScreen(maxX + 8, maxY + 8);
            if (minScreen != null && maxScreen != null) {
                double sx = Math.max(minScreen.getX(),
                        Math.min(event.getScreenX(), maxScreen.getX() + Config.CURSOR_SIZE / 2));
                double sy = Math.max(minScreen.getY(),
                        Math.min(event.getScreenY(), maxScreen.getY() + Config.CURSOR_SIZE / 2));
                if (sx != event.getScreenX() || sy != event.getScreenY()) {
                    robot.mouseMove(sx, sy);
                }
            }
        });

        gameScene.setOnMousePressed(event -> {
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
        });
    }

    // ---- HUD layout matching the Sound Space screenshot -------------------
    private void buildHud() {
        double half = Config.SQUARE_SIZE / 2;

        // -- left column: COMBO, score, misses, notes -----------------------
        double lx = -half - 140;
        double ly = -half - 10;

        comboLabel = hudText("COMBO", 12, Config.TEXT_DIM, lx, ly);
        comboValue = hudText("0", 38, Config.TEXT_PRIMARY, lx, ly + 38);
        comboValue.setFont(Font.font("Consolas", FontWeight.BOLD, 38));

        scoreValue = hudText("0", 18, Config.TEXT_DIM, lx, ly + 70);

        missesLabel = hudText("Misses", 12, Config.TEXT_DIM, lx, ly + 100);
        missesValue = hudText("0", 18, Config.TEXT_PRIMARY, lx, ly + 120);

        notesLabel = hudText("Notes", 12, Config.TEXT_DIM, lx, ly + 150);
        notesValue = hudText("0/0", 18, Config.TEXT_PRIMARY, lx, ly + 170);

        // -- right column: multiplier ring + accuracy -----------------------
        double rx = half + 75;
        double ry = -half + 30;

        // multiplier arc (progress ring)
        multiplierArc = new Arc(rx, ry, 38, 38, 90, 0);
        multiplierArc.setType(ArcType.OPEN);
        multiplierArc.setFill(null);
        multiplierArc.setStroke(Config.ACCENT);
        multiplierArc.setStrokeWidth(4);
        multiplierArc.setStrokeLineCap(StrokeLineCap.ROUND);

        Circle ringBg = new Circle(rx, ry, 38);
        ringBg.setFill(null);
        ringBg.setStroke(Color.web("#ffffff", 0.12));
        ringBg.setStrokeWidth(4);

        multiplierText = new Text("x1");
        multiplierText.setFont(Font.font("Consolas", FontWeight.BOLD, 24));
        multiplierText.setFill(Config.TEXT_PRIMARY);
        multiplierText.setBoundsType(TextBoundsType.VISUAL);
        multiplierText.setX(rx - 14);
        multiplierText.setY(ry + 8);

        gameRoot.getChildren().addAll(ringBg, multiplierArc, multiplierText);

        // accuracy
        accuracyText = hudText("100.00%", 20, Color.web("#4ade80"), rx - 24, ry + 70);
        accuracyText.setFont(Font.font("Consolas", FontWeight.BOLD, 20));

        // -- top center: timer ----------------------------------------------
        timerText = hudText("0:00", 18, Config.TEXT_PRIMARY, -20, -half - 30);
        timerText.setFont(Font.font("Consolas", FontWeight.BOLD, 18));

        // -- bottom: health bar ---------------------------------------------
        double barW = Config.SQUARE_SIZE * 1.1;
        double barH = 8;
        double barX = -barW / 2;
        double barY = half + 22;

        healthTrack = new Rectangle(barX, barY, barW, barH);
        healthTrack.setArcWidth(barH);
        healthTrack.setArcHeight(barH);
        healthTrack.setFill(Color.web("#1e293b"));

        healthBar = new Rectangle(barX, barY, barW, barH);
        healthBar.setArcWidth(barH);
        healthBar.setArcHeight(barH);
        healthBar.setFill(Config.HEALTH_GOOD);

        gameRoot.getChildren().addAll(healthTrack, healthBar);
    }

    private Text hudText(String text, double size, Color fill, double x, double y) {
        Text t = new Text(text);
        t.setFont(Font.font("Consolas", size));
        t.setFill(fill);
        t.setX(x);
        t.setY(y);
        t.setBoundsType(TextBoundsType.VISUAL);
        gameRoot.getChildren().add(t);
        return t;
    }

    // -----------------------------------------------------------------------
    //  Launching a song
    // -----------------------------------------------------------------------

    private void launchGame(String song, String difficulty) {
        double[] timings = BeatMap.getTimings(song, difficulty);
        if (timings == null || timings.length == 0) return;

        state.reset();
        colorNum = 0;

        // reset HUD
        updateHud();
        healthBar.setWidth(healthTrack.getWidth());

        if (menuMediaPlayer != null) menuMediaPlayer.stop();
        primaryStage.setScene(gameScene);

        totalMapNotes = timings.length;
        if (BeatMap.shouldSkipIntro(song, difficulty)) {
            totalMapNotes -= 34;
        }

        // 2 second count-in, then spawn notes; music starts 2.25 s into the timeline
        Timeline delayTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            startNotes(timings, song, difficulty);
            new Timeline(new KeyFrame(Duration.seconds(2.25), ev -> playSong(song))).play();
        }));
        delayTimeline.play();
    }

    private void playSong(String song) {
        try {
            String path;
            if (customSongPaths.containsKey(song)) {
                // Custom song – audio path stored in customSongPaths
                path = customSongPaths.get(song);
            } else {
                // Built-in song – look in src/
                path = "src/" + song + ".mp3";
            }
            Media sound = new Media(new File(path).toURI().toString());
            mediaPlayer = new MediaPlayer(sound);
            mediaPlayer.play();
        } catch (Exception ignored) { }
    }

    // -----------------------------------------------------------------------
    //  Note spawning & timing
    // -----------------------------------------------------------------------

    private void startNotes(double[] timings, String song, String difficulty) {
        gameTimeline = new Timeline();
        boolean skip = BeatMap.shouldSkipIntro(song, difficulty);

        // End-of-song transition
        Timeline endDelay = new Timeline(new KeyFrame(
                Duration.seconds(timings[timings.length - 1] + 5),
                e -> showEndScreen()));
        endDelay.play();

        for (int i = 0; i < timings.length; i++) {
            if (skip && i < 34) continue;
            KeyFrame kf = new KeyFrame(Duration.seconds(timings[i]), event -> {
                spawnNote(colorNum % 2);
                colorNum++;
            });
            gameTimeline.getKeyFrames().add(kf);
        }
        gameTimeline.play();

        // timer updater
        long startMs = System.currentTimeMillis();
        AnimationTimer timer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (state.isFailed()) { stop(); return; }
                long elapsed = System.currentTimeMillis() - startMs;
                int secs = (int)(elapsed / 1000);
                timerText.setText(String.format("%d:%02d", secs / 60, secs % 60));
            }
        };
        timer.start();
    }

    // -----------------------------------------------------------------------
    //  Note spawn + animation
    // -----------------------------------------------------------------------

    private void spawnNote(int col) {
        if (state.isFailed()) return;

        double x = cellPositions[(int) (Math.random() * 3)];
        double y = cellPositions[(int) (Math.random() * 3)];
        double sz = Config.SQUARE_SIZE / 3;

        Rectangle note = new Rectangle(x, y, sz, sz);
        note.setFill(null);
        Color noteColor = (col == 0) ? Config.NOTE_PINK : Config.NOTE_CYAN;
        note.setStroke(noteColor);
        note.setStrokeWidth(4);
        note.setArcWidth(22);
        note.setArcHeight(22);
        note.setTranslateZ(10000);
        note.setEffect(new DropShadow(BlurType.GAUSSIAN, noteColor, 12, 0.3, 0, 0));

        gameRoot.getChildren().add(note);

        // fly toward the player
        TranslateTransition fly = new TranslateTransition(
                Duration.seconds(Config.NOTE_TRAVEL_SECONDS), note);
        fly.setFromZ(10000);
        fly.setToZ(0);
        fly.setInterpolator(Interpolator.LINEAR);

        // stroke fades in as the note approaches
        Color transparent = noteColor.deriveColor(0, 1, 1, 0);
        StrokeTransition stroke = new StrokeTransition(
                Duration.seconds(Config.NOTE_TRAVEL_SECONDS), note, transparent, noteColor);

        fly.setOnFinished(event -> handleNoteArrival(note));
        stroke.play();
        fly.play();
    }

    private void handleNoteArrival(Rectangle note) {
        if (state.isFailed()) {
            gameRoot.getChildren().remove(note);
            return;
        }

        Bounds noteBounds   = note.getBoundsInParent();
        Bounds cursorBounds = cursor.getBoundsInParent();

        if (noteBounds.intersects(cursorBounds)) {
            state.registerHit();
            playSound("src/hit.wav");
            spawnHitFeedback(note, true);
        } else {
            state.registerMiss();
            playSound("src/miss.wav");
            spawnHitFeedback(note, false);
        }

        updateHud();
        gameRoot.getChildren().remove(note);

        if (state.isFailed()) {
            if (mediaPlayer != null) mediaPlayer.stop();
            showFailScreen();
        }
    }

    // -----------------------------------------------------------------------
    //  HUD updates
    // -----------------------------------------------------------------------

    private void updateHud() {
        comboValue.setText(String.valueOf(state.getCombo()));
        scoreValue.setText(String.format("%,d", state.getScore()));
        missesValue.setText(String.valueOf(state.getTotalNotes() - state.getSuccessfulHits()));
        notesValue.setText(state.getTotalNotes() + "/" + totalMapNotes);

        int mult = state.getMultiplier();
        multiplierText.setText("x" + mult);
        multiplierText.setX(multiplierArc.getCenterX() - (mult >= 10 ? 18 : 14));
        multiplierArc.setLength(-360 * state.getMultiplierProgress());

        accuracyText.setText(String.format("%.2f%%", state.getAccuracy()));
        accuracyText.setFill(state.getAccuracyColor());

        healthBar.setWidth(healthTrack.getWidth() * state.getHealthFraction());
        healthBar.setFill(state.getHealthColor());
    }

    /** Small "+score" or "X" feedback at the note position. */
    private void spawnHitFeedback(Rectangle note, boolean hit) {
        Text fb = new Text(hit ? ("+" + Config.BASE_HIT_POINTS * state.getMultiplier()) : "✕");
        fb.setFont(Font.font("Consolas", FontWeight.BOLD, hit ? 16 : 22));
        fb.setFill(hit ? Config.HEALTH_GOOD : Config.HEALTH_BAD);
        fb.setX(note.getX() + note.getWidth() / 2 - 10);
        fb.setY(note.getY() + note.getHeight() / 2);
        fb.setBoundsType(TextBoundsType.VISUAL);
        gameRoot.getChildren().add(fb);

        TranslateTransition rise = new TranslateTransition(Duration.millis(600), fb);
        rise.setByY(-30);
        FadeTransition fade = new FadeTransition(Duration.millis(600), fb);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setOnFinished(e -> gameRoot.getChildren().remove(fb));
        rise.play();
        fade.play();
    }

    // -----------------------------------------------------------------------
    //  End / Fail screens
    // -----------------------------------------------------------------------

    private void showEndScreen() {
        VBox layout = endScreenBase();

        Text grade = new Text(state.getLetterGrade());
        grade.setFont(Font.font("Consolas", FontWeight.BOLD, 220));
        grade.setFill(state.getGradeColor());
        grade.setEffect(new Glow(0.5));

        Text acc = new Text("Accuracy: " + String.format("%.2f%%", state.getAccuracy()));
        acc.setFont(Font.font("Consolas", 48));
        acc.setFill(Config.TEXT_PRIMARY);

        Text combo = new Text("Max Combo: " + state.getMaxCombo());
        combo.setFont(Font.font("Consolas", 48));
        combo.setFill(Config.TEXT_PRIMARY);

        Text score = new Text("Score: " + String.format("%,d", state.getScore()));
        score.setFont(Font.font("Consolas", 36));
        score.setFill(Config.ACCENT);

        layout.getChildren().addAll(grade, acc, combo, score, restartButton());
        primaryStage.setScene(new Scene(layout, Config.WIDTH, Config.HEIGHT));

        playSound("src/new_best.wav");
    }

    private void showFailScreen() {
        VBox layout = endScreenBase();

        Text fail = new Text("FAILED");
        fail.setFont(Font.font("Consolas", FontWeight.BOLD, 160));
        fail.setFill(Config.HEALTH_BAD);
        fail.setEffect(new Glow(0.4));

        Text hint = new Text("Better luck next time!");
        hint.setFont(Font.font("Consolas", 32));
        hint.setFill(Config.TEXT_DIM);

        layout.getChildren().addAll(fail, hint, restartButton());
        primaryStage.setScene(new Scene(layout, Config.WIDTH, Config.HEIGHT));
    }

    private VBox endScreenBase() {
        VBox v = new VBox(24);
        v.setPrefSize(Config.WIDTH, Config.HEIGHT);
        v.setAlignment(Pos.CENTER);
        v.setStyle("-fx-background-color: #0a0e1a;");
        return v;
    }

    private Button restartButton() {
        Button btn = new Button("↻  RESTART");
        btn.setStyle(
                "-fx-background-color: #22d3ee; -fx-text-fill: #05070f;" +
                "-fx-font-size: 20px; -fx-font-weight: bold; -fx-padding: 10 36;" +
                "-fx-background-radius: 6; -fx-cursor: hand;");
        btn.setOnAction(e -> {
            state.reset();
            if (menuMediaPlayer != null) menuMediaPlayer.play();
            primaryStage.setScene(menuScene);
        });
        return btn;
    }

    // -----------------------------------------------------------------------
    //  Audio helpers
    // -----------------------------------------------------------------------

    private void playSound(String path) {
        try {
            Media m = new Media(new File(path).toURI().toString());
            MediaPlayer p = new MediaPlayer(m);
            p.setOnEndOfMedia(p::dispose);
            p.play();
        } catch (Exception ignored) { }
    }

    // -----------------------------------------------------------------------
    //  Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        launch(args);
    }
}
