package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Encode les images en MP4 H.264 via un processus ffmpeg alimenté en rawvideo RGB24
 * sur son entrée standard. Le processus n'est lancé qu'à la première image, quand la
 * résolution est connue.
 */
public final class FfmpegSink implements FrameSink {

    private static final String FFMPEG = "ffmpeg";
    /** Facteur CRF x264 : 18 = visuellement sans perte sur du pixel art. */
    private static final int CRF = 18;

    private final Path output;
    private final int fps;
    private final int scale;
    private Process process;
    private OutputStream stdin;
    private byte[] rgb;

    public FfmpegSink(Path output, int fps, int scale) {
        this.output = output;
        this.fps = fps;
        this.scale = scale;
    }

    /** Vérifie que ffmpeg est exécutable ; à appeler avant d'armer une capture. */
    public static void requireFfmpeg() {
        try {
            Process p = new ProcessBuilder(FFMPEG, "-version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor() != 0) throw new IllegalStateException("ffmpeg -version a échoué");
        } catch (IOException e) {
            throw new IllegalStateException("ffmpeg introuvable sur le PATH : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompu");
        }
    }

    private void start(int width, int height) {
        List<String> cmd = List.of(FFMPEG, "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", width + "x" + height, "-r", Integer.toString(fps),
                "-i", "-",
                "-vf", "scale=iw*" + scale + ":ih*" + scale + ":flags=neighbor,format=yuv420p",
                "-c:v", "libx264", "-profile:v", "main", "-crf", Integer.toString(CRF),
                "-movflags", "+faststart", output.toString());
        try {
            process = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("lancement de ffmpeg impossible : " + e.getMessage());
        }
        stdin = process.getOutputStream();
        rgb = new byte[width * height * 3];
    }

    @Override
    public void accept(Machine.Frame f) {
        if (process == null) start(f.width(), f.height());
        int[] px = f.pixels();
        for (int i = 0, o = 0; i < px.length; i++) {
            int c = px[i];
            rgb[o++] = (byte) (c >> 16);
            rgb[o++] = (byte) (c >> 8);
            rgb[o++] = (byte) c;
        }
        try {
            stdin.write(rgb);
        } catch (IOException e) {
            throw new IllegalStateException("ffmpeg a fermé son entrée : " + e.getMessage());
        }
    }

    @Override
    public long close() {
        if (process == null) return 0;
        try {
            stdin.close();
            int rc = process.waitFor();
            if (rc != 0) throw new IllegalStateException("ffmpeg a terminé avec le code " + rc);
            return Files.size(output);
        } catch (IOException e) {
            throw new IllegalStateException("finalisation ffmpeg : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompu");
        } finally {
            process = null;
        }
    }
}
