package soundspace;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Spectral-flux beat detector inspired by Essentia's RhythmExtractor2013.
 * <p>
 * Algorithm outline (similar to "multifeature" mode):
 * <ol>
 *   <li>Decode audio to mono PCM samples</li>
 *   <li>Window into overlapping frames, compute magnitude spectrum via DFT</li>
 *   <li>Compute spectral flux (sum of positive freq-bin differences)</li>
 *   <li>Adaptive thresholding on the flux curve to find onset peaks</li>
 *   <li>Return peak timestamps as beat positions (seconds)</li>
 * </ol>
 *
 * Supports WAV natively.  MP3/OGG/FLAC require conversion to WAV first
 * (the caller can use {@code ffmpeg} or JavaFX's MediaPlayer for playback
 * while this class handles analysis on a converted WAV).
 */
public final class BeatDetector {

    private BeatDetector() { }

    // ---- tuneable parameters ----------------------------------------------

    /** FFT frame size (power of 2). */
    private static final int FRAME_SIZE = 1024;

    /** Hop between consecutive frames. */
    private static final int HOP_SIZE = 512;

    /** Number of frames used for the adaptive threshold window. */
    private static final int THRESHOLD_WINDOW = 10;

    /** Multiplier above local mean to count as an onset. */
    private static final double THRESHOLD_MULTIPLIER = 1.4;

    /** Minimum gap between two detected beats (seconds). */
    private static final double MIN_BEAT_GAP = 0.12;

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Analyse a WAV file and return an array of beat timestamps in seconds.
     *
     * @param wavFile path to a WAV audio file
     * @return sorted array of beat positions (seconds from start)
     * @throws IOException            if the file can't be read
     * @throws UnsupportedAudioFileException if the format isn't supported
     */
    public static double[] detectBeats(File wavFile)
            throws IOException, UnsupportedAudioFileException {

        float[] samples = loadMonoSamples(wavFile);
        if (samples.length < FRAME_SIZE) {
            return new double[0];
        }

        float sampleRate = getSampleRate(wavFile);
        double[] flux = spectralFlux(samples);
        return pickPeaks(flux, sampleRate);
    }

    /**
     * Convenience: detect beats from raw mono samples at a given sample rate.
     */
    public static double[] detectBeats(float[] monoSamples, float sampleRate) {
        if (monoSamples.length < FRAME_SIZE) return new double[0];
        double[] flux = spectralFlux(monoSamples);
        return pickPeaks(flux, sampleRate);
    }

    // -----------------------------------------------------------------------
    //  Audio loading
    // -----------------------------------------------------------------------

    private static float getSampleRate(File wavFile)
            throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile)) {
            return ais.getFormat().getSampleRate();
        }
    }

    /**
     * Load an audio file and return mono float samples in [-1, 1].
     */
    static float[] loadMonoSamples(File file)
            throws IOException, UnsupportedAudioFileException {

        AudioInputStream rawStream = AudioSystem.getAudioInputStream(file);
        AudioFormat srcFmt = rawStream.getFormat();

        // Decode to 16-bit signed PCM mono if needed
        AudioFormat decodedFmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                srcFmt.getSampleRate(),
                16,                     // 16-bit
                1,                      // mono
                2,                      // frame size = 2 bytes (16-bit mono)
                srcFmt.getSampleRate(),
                false                   // little-endian
        );

        AudioInputStream decoded;
        if (AudioSystem.isConversionSupported(decodedFmt, srcFmt)) {
            decoded = AudioSystem.getAudioInputStream(decodedFmt, rawStream);
        } else {
            // Try going through PCM first (e.g. for u-law, a-law)
            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    srcFmt.getSampleRate(),
                    16, srcFmt.getChannels(), srcFmt.getChannels() * 2,
                    srcFmt.getSampleRate(), false);
            AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcm, rawStream);

            // Then down-mix to mono
            if (pcm.getChannels() > 1) {
                decoded = AudioSystem.getAudioInputStream(decodedFmt, pcmStream);
            } else {
                decoded = pcmStream;
            }
        }

        // Read all bytes
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = decoded.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        decoded.close();
        rawStream.close();

        byte[] pcmBytes = bos.toByteArray();
        int numSamples = pcmBytes.length / 2;
        float[] samples = new float[numSamples];

        ByteBuffer bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            samples[i] = bb.getShort() / 32768.0f;
        }
        return samples;
    }

    // -----------------------------------------------------------------------
    //  Spectral flux computation
    // -----------------------------------------------------------------------

    /**
     * Compute spectral flux: for each frame, sum positive differences in
     * magnitude spectrum bins compared to the previous frame.
     */
    private static double[] spectralFlux(float[] samples) {
        int numFrames = 1 + (samples.length - FRAME_SIZE) / HOP_SIZE;
        double[] flux = new double[numFrames];

        double[] prevMag = new double[FRAME_SIZE / 2 + 1];
        double[] window = hannWindow(FRAME_SIZE);

        for (int f = 0; f < numFrames; f++) {
            int offset = f * HOP_SIZE;

            // Apply Hann window and get real/imag arrays
            double[] re = new double[FRAME_SIZE];
            double[] im = new double[FRAME_SIZE];
            for (int i = 0; i < FRAME_SIZE; i++) {
                int idx = offset + i;
                re[i] = (idx < samples.length ? samples[idx] : 0) * window[i];
                im[i] = 0;
            }

            // In-place FFT
            fft(re, im);

            // Magnitude spectrum (only positive freqs)
            double[] mag = new double[FRAME_SIZE / 2 + 1];
            for (int i = 0; i <= FRAME_SIZE / 2; i++) {
                mag[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            }

            // Spectral flux = sum of positive differences
            double sf = 0;
            for (int i = 0; i < mag.length; i++) {
                double diff = mag[i] - prevMag[i];
                if (diff > 0) sf += diff;
            }
            flux[f] = sf;

            System.arraycopy(mag, 0, prevMag, 0, mag.length);
        }
        return flux;
    }

    // -----------------------------------------------------------------------
    //  Onset peak picking with adaptive threshold
    // -----------------------------------------------------------------------

    private static double[] pickPeaks(double[] flux, float sampleRate) {
        List<Double> beats = new ArrayList<>();
        double lastBeatTime = -1;

        for (int i = 1; i < flux.length - 1; i++) {
            // Local mean over a window centred on i
            int start = Math.max(0, i - THRESHOLD_WINDOW);
            int end   = Math.min(flux.length, i + THRESHOLD_WINDOW + 1);
            double mean = 0;
            for (int j = start; j < end; j++) mean += flux[j];
            mean /= (end - start);

            double threshold = mean * THRESHOLD_MULTIPLIER;

            // Must be a local maximum AND above threshold
            if (flux[i] > threshold && flux[i] > flux[i - 1] && flux[i] >= flux[i + 1]) {
                double timeSec = (double) i * HOP_SIZE / sampleRate;
                if (timeSec - lastBeatTime >= MIN_BEAT_GAP) {
                    beats.add(timeSec);
                    lastBeatTime = timeSec;
                }
            }
        }

        return beats.stream().mapToDouble(Double::doubleValue).toArray();
    }

    // -----------------------------------------------------------------------
    //  Hann window
    // -----------------------------------------------------------------------

    private static double[] hannWindow(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (size - 1)));
        }
        return w;
    }

    // -----------------------------------------------------------------------
    //  Radix-2 Cooley–Tukey FFT  (in-place, arrays must be power-of-2 length)
    // -----------------------------------------------------------------------

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        if (n == 1) return;

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }

        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;

                    double tRe = curRe * re[v] - curIm * im[v];
                    double tIm = curRe * im[v] + curIm * re[v];

                    re[v] = re[u] - tRe;
                    im[v] = im[u] - tIm;
                    re[u] += tRe;
                    im[u] += tIm;

                    double newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }
    }
}
