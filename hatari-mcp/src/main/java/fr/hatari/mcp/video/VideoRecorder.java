package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * État d'une capture vidéo : échantillonne l'écran toutes les {@code every} trames
 * émulées pendant que la machine avance, relaie le son du cœur, et pousse le tout vers
 * un {@link FrameSink}. Le temps réel émulé est mesuré en cycles CPU : à la fermeture,
 * la vidéo (cadence nominale 50/every) est recalée dessus pour rester synchrone avec le
 * son, quelle que soit la fréquence VBL réelle (50, 60 ou 71 Hz).
 * Une seule capture à la fois ; {@link #stop()} est idempotent.
 */
public final class VideoRecorder {

    /** Cadence VBL nominale servant à fixer la cadence vidéo. */
    public static final int NOMINAL_VBL_HZ = 50;

    private final long cpuHz;
    private FrameSink sink;
    private String path;
    private int every;
    private int fps;
    private boolean audio;
    private int audioHz;
    private int pending;        // trames émulées depuis la dernière image
    private long emulated;      // trames émulées filmées
    private long frames;        // images poussées
    private long audioSamples;  // échantillons stéréo relayés
    private long cycles0, cyclesLast;
    private Map<String, Object> last;

    /** @param cpuHz fréquence du CPU émulé, base de mesure du temps réel */
    public VideoRecorder(long cpuHz) { this.cpuHz = cpuHz; }

    public synchronized boolean active() { return sink != null; }

    /**
     * Arme la capture ; erreur si une capture est déjà en cours.
     *
     * @param audioHz  fréquence du son relayé, 0 pour une capture muette
     * @param cyclesNow compteur de cycles CPU au moment de l'armement
     */
    public synchronized void arm(FrameSink sink, String path, int every, int audioHz, long cyclesNow) {
        if (this.sink != null) {
            throw new IllegalStateException("une capture est déjà en cours (" + this.path
                    + ") : appeler stop_video_capture d'abord");
        }
        if (every < 1) throw new IllegalArgumentException("every doit être >= 1");
        this.sink = sink;
        this.path = path;
        this.every = every;
        this.fps = Math.max(1, Math.round((float) NOMINAL_VBL_HZ / every));
        this.audioHz = audioHz;
        this.audio = audioHz > 0;
        this.pending = 0;
        this.emulated = 0;
        this.frames = 0;
        this.audioSamples = 0;
        this.cycles0 = cyclesNow;
        this.cyclesLast = cyclesNow;
    }

    public synchronized int fps() { return fps; }

    /** Pas de trame maximal à exécuter d'un coup pour ne rater aucun échantillon. */
    public synchronized int chunk() {
        return sink == null ? Integer.MAX_VALUE : every - pending;
    }

    /** Notifie {@code n} trames émulées ; échantillonne l'écran de {@code m} quand le pas est atteint. */
    public synchronized void advanced(Machine m, int n) {
        if (sink == null || n <= 0) return;
        emulated += n;
        pending += n;
        cyclesLast = m.cycleCount();
        while (pending >= every) {
            pending -= every;
            sink.accept(m.frame());
            frames++;
        }
    }

    /** Relaie un lot d'échantillons du cœur (appelé depuis l'upcall audio, même thread). */
    public synchronized void audio(short[] interleavedStereo) {
        if (sink == null || !audio) return;
        sink.audio(interleavedStereo);
        audioSamples += interleavedStereo.length / 2;
    }

    /** Secondes réellement émulées depuis l'armement, d'après les cycles CPU. */
    private double realSeconds() { return (cyclesLast - cycles0) / (double) cpuHz; }

    /** Durée nominale de la vidéo telle qu'encodée. */
    private double nominalSeconds() { return frames / (double) fps; }

    /** Arrête et finalise ; retourne le compte rendu (le même si déjà arrêtée). */
    public synchronized Map<String, Object> stop() {
        if (sink == null) {
            if (last == null) throw new IllegalStateException("aucune capture n'a été armée");
            return last;
        }
        Map<String, Object> out = status();
        double scale = frames > 0 && realSeconds() > 0 ? realSeconds() / nominalSeconds() : 1.0;
        long bytes;
        try {
            bytes = sink.close(scale);
        } finally {
            sink = null;
        }
        out.put("active", false);
        out.put("bytes", bytes);
        out.put("video_time_scale", Math.round(scale * 10000.0) / 10000.0);
        last = out;
        return out;
    }

    public synchronized Map<String, Object> status() {
        if (sink == null && last != null) return new LinkedHashMap<>(last);
        if (sink == null) throw new IllegalStateException("aucune capture n'a été armée");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", true);
        out.put("path", path);
        out.put("frames", frames);
        out.put("emulated_frames", emulated);
        out.put("seconds", Math.round(realSeconds() * 100.0) / 100.0);
        out.put("audio", audio);
        out.put("audio_samples", audioSamples);
        out.put("audio_seconds", audio ? Math.round(audioSamples * 100.0 / audioHz) / 100.0 : 0.0);
        return out;
    }
}
