package soundspace;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages custom (user-imported) songs.  Each custom song is stored as a
 * small {@code .beats} text file alongside the audio, containing the song
 * name, the path to the audio file, and the detected beat timestamps.
 *
 * <p>Layout on disk:
 * <pre>
 *   custom_songs/
 *     MySong.beats      – metadata + timing array
 *     MySong.wav        – the audio file (or .mp3)
 * </pre>
 *
 * The {@code .beats} format is intentionally trivial (no JSON library needed):
 * <pre>
 *   name=MySong
 *   audioPath=custom_songs/MySong.wav
 *   beats=0.423,0.891,1.302,...
 * </pre>
 */
public final class CustomSongManager {

    private CustomSongManager() { }

    /** Directory where custom songs and .beats files live. */
    public static final String CUSTOM_DIR = "custom_songs";

    // -----------------------------------------------------------------------
    //  Data holder
    // -----------------------------------------------------------------------

    public static class CustomSong {
        public final String   name;
        public final String   audioPath;
        public final double[] beats;

        public CustomSong(String name, String audioPath, double[] beats) {
            this.name      = name;
            this.audioPath = audioPath;
            this.beats     = beats;
        }
    }

    // -----------------------------------------------------------------------
    //  Save a newly imported song
    // -----------------------------------------------------------------------

    /**
     * Copy the audio file into {@code custom_songs/}, analyse beats, and write
     * a {@code .beats} file.  Returns the new {@link CustomSong}.
     *
     * @param sourceAudio the original file the user selected
     * @return the saved custom song (with beat timings)
     * @throws Exception on I/O or analysis failure
     */
    public static CustomSong importSong(File sourceAudio) throws Exception {
        Path dir = Paths.get(CUSTOM_DIR);
        Files.createDirectories(dir);

        // Derive a clean name from the filename (strip extension)
        String fileName = sourceAudio.getName();
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.'))
                : "";

        // Copy audio into custom_songs/ (skip if already there)
        Path dest = dir.resolve(fileName);
        if (!dest.toAbsolutePath().equals(sourceAudio.toPath().toAbsolutePath())) {
            Files.copy(sourceAudio.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
        }

        // Determine what file to hand to the beat detector.
        // BeatDetector needs WAV.  If the source is not WAV, try to find
        // a WAV version or tell the user.
        File analysisFile;
        if (ext.equalsIgnoreCase(".wav")) {
            analysisFile = dest.toFile();
        } else {
            // Attempt conversion via ffmpeg (commonly available)
            File wavDest = dir.resolve(baseName + ".wav").toFile();
            boolean converted = convertToWav(dest.toFile(), wavDest);
            if (converted && wavDest.exists()) {
                analysisFile = wavDest;
            } else {
                // Fall back: use simple BPM grid (120 BPM, 0.5s interval)
                // until a WAV version is available
                System.err.println("[CustomSongManager] Could not convert to WAV. " +
                        "Using fallback 120 BPM grid. Install ffmpeg for auto-conversion.");
                double duration = estimateDuration(dest.toFile());
                double[] fallbackBeats = generateBpmGrid(120, duration);
                CustomSong song = new CustomSong(baseName, dest.toString(), fallbackBeats);
                writeBeatsFile(song);
                return song;
            }
        }

        // Run beat detection
        double[] beats = BeatDetector.detectBeats(analysisFile);

        CustomSong song = new CustomSong(baseName, dest.toString(), beats);
        writeBeatsFile(song);
        return song;
    }

    // -----------------------------------------------------------------------
    //  Load all saved custom songs
    // -----------------------------------------------------------------------

    /** Scan {@code custom_songs/} for {@code .beats} files and load them. */
    public static List<CustomSong> loadAll() {
        List<CustomSong> songs = new ArrayList<>();
        File dir = new File(CUSTOM_DIR);
        if (!dir.isDirectory()) return songs;

        File[] beatsFiles = dir.listFiles((d, name) -> name.endsWith(".beats"));
        if (beatsFiles == null) return songs;

        for (File bf : beatsFiles) {
            try {
                songs.add(readBeatsFile(bf));
            } catch (Exception e) {
                System.err.println("[CustomSongManager] Skipping " + bf.getName() + ": " + e.getMessage());
            }
        }
        return songs;
    }

    // -----------------------------------------------------------------------
    //  .beats file I/O
    // -----------------------------------------------------------------------

    private static void writeBeatsFile(CustomSong song) throws IOException {
        Path dir = Paths.get(CUSTOM_DIR);
        Files.createDirectories(dir);
        Path file = dir.resolve(song.name + ".beats");

        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(song.name).append('\n');
        sb.append("audioPath=").append(song.audioPath).append('\n');
        sb.append("beats=");
        for (int i = 0; i < song.beats.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%.6f", song.beats[i]));
        }
        sb.append('\n');

        Files.writeString(file, sb.toString());
    }

    private static CustomSong readBeatsFile(File file) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file.toPath())) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }

        String name      = props.getOrDefault("name", "Unknown");
        String audioPath = props.getOrDefault("audioPath", "");
        String beatsStr  = props.getOrDefault("beats", "");

        double[] beats;
        if (beatsStr.isEmpty()) {
            beats = new double[0];
        } else {
            String[] parts = beatsStr.split(",");
            beats = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                beats[i] = Double.parseDouble(parts[i].trim());
            }
        }

        return new CustomSong(name, audioPath, beats);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /** Try to convert an audio file to WAV using ffmpeg. */
    private static boolean convertToWav(File src, File dest) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", src.getAbsolutePath(),
                    "-ar", "44100", "-ac", "1", "-sample_fmt", "s16",
                    dest.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Consume output to prevent blocking
            try (InputStream is = proc.getInputStream()) {
                is.readAllBytes();
            }

            int exitCode = proc.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Rough duration estimate based on file size (used as fallback). */
    private static double estimateDuration(File audioFile) {
        // Very rough: assume ~128kbps → 16 KB/s
        long bytes = audioFile.length();
        return Math.max(30, bytes / 16000.0);
    }

    /** Generate evenly spaced beats at a given BPM for a given duration. */
    private static double[] generateBpmGrid(double bpm, double durationSec) {
        double interval = 60.0 / bpm;
        List<Double> beats = new ArrayList<>();
        for (double t = interval; t < durationSec; t += interval) {
            beats.add(t);
        }
        return beats.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
