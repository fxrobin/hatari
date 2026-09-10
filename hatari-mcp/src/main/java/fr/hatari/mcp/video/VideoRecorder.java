package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * État d'une capture vidéo : échantillonne l'écran toutes les {@code every} trames
 * émulées pendant que la machine avance, et pousse les images vers un {@link FrameSink}.
 * Une seule capture à la fois ; {@link #stop()} est idempotent.
 */
public final class VideoRecorder {

    private FrameSink sink;
    private String path;
    private int every;
    private int fps;
    private int pending;        // trames émulées depuis la dernière image
    private long emulated;      // trames émulées filmées
    private long frames;        // images poussées
    private Map<String, Object> last;

    public synchronized boolean active() { return sink != null; }

    /** Arme la capture ; erreur si une capture est déjà en cours. */
    public synchronized void arm(FrameSink sink, String path, int every, int fps) {
        if (this.sink != null) {
            throw new IllegalStateException("une capture est déjà en cours (" + this.path
                    + ") : appeler stop_video_capture d'abord");
        }
        if (every < 1) throw new IllegalArgumentException("every doit être >= 1");
        this.sink = sink;
        this.path = path;
        this.every = every;
        this.fps = fps;
        this.pending = 0;
        this.emulated = 0;
        this.frames = 0;
    }

    /** Pas de trame maximal à exécuter d'un coup pour ne rater aucun échantillon. */
    public synchronized int chunk() {
        return sink == null ? Integer.MAX_VALUE : every - pending;
    }

    /** Notifie {@code n} trames émulées ; échantillonne l'écran de {@code m} quand le pas est atteint. */
    public synchronized void advanced(Machine m, int n) {
        if (sink == null || n <= 0) return;
        emulated += n;
        pending += n;
        while (pending >= every) {
            pending -= every;
            sink.accept(m.frame());
            frames++;
        }
    }

    /** Arrête et finalise ; retourne le compte rendu (le même si déjà arrêtée). */
    public synchronized Map<String, Object> stop() {
        if (sink == null) {
            if (last == null) throw new IllegalStateException("aucune capture n'a été armée");
            return last;
        }
        Map<String, Object> out = status();
        long bytes;
        try {
            bytes = sink.close();
        } finally {
            sink = null;
        }
        out.put("active", false);
        out.put("bytes", bytes);
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
        out.put("seconds", Math.round(frames * 100.0 / fps) / 100.0);
        return out;
    }
}
