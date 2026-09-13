package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Encode les images en H.264 via un processus ffmpeg alimenté en rawvideo RGB24 sur son
 * entrée standard (fichier vidéo temporaire), écrit le son en PCM brut dans un second
 * fichier temporaire, puis à la fermeture multiplexe les deux (AAC) dans le MP4 final en
 * appliquant {@code -itsscale} à la vidéo pour la caler sur le temps réel émulé.
 */
public final class FfmpegSink implements FrameSink {

    private static final String FFMPEG = "ffmpeg";
    /** Facteur CRF x264 : 18 = visuellement sans perte sur du pixel art. */
    private static final int CRF = 18;
    private static final String AUDIO_BITRATE = "192k";
    /** Écart de cadence en deçà duquel on ne recale pas la vidéo. */
    private static final double SCALE_TOLERANCE = 0.001;

    private final Path output;
    private final Path videoTmp;
    private final Path audioTmp;
    private final int fps;
    private final int scale;
    private final int audioHz;
    private Process process;
    private OutputStream stdin;
    private OutputStream audioOut;
    private long audioBytes;
    private byte[] rgb;

    /**
     * @param audioHz fréquence d'échantillonnage du son, 0 pour une vidéo muette
     */
    public FfmpegSink(Path output, int fps, int scale, int audioHz) {
        this.output = output;
        this.videoTmp = output.resolveSibling(output.getFileName() + ".video.tmp.mp4");
        this.audioTmp = output.resolveSibling(output.getFileName() + ".audio.s16le");
        this.fps = fps;
        this.scale = scale;
        this.audioHz = audioHz;
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

    private static Process ffmpeg(List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(FFMPEG, "-loglevel", "error", "-y"));
        cmd.addAll(args);
        return new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private void start(int width, int height) {
        try {
            process = ffmpeg(List.of(
                    "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", width + "x" + height,
                    "-r", Integer.toString(fps), "-i", "-",
                    "-vf", "scale=iw*" + scale + ":ih*" + scale + ":flags=neighbor,format=yuv420p",
                    "-c:v", "libx264", "-profile:v", "main", "-crf", Integer.toString(CRF),
                    videoTmp.toString()));
            if (audioHz > 0) audioOut = new BufferedOutputStream(Files.newOutputStream(audioTmp), 1 << 16);
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
    public void audio(short[] pcm) {
        if (audioOut == null) return;
        ByteBuffer b = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.asShortBuffer().put(pcm);
        try {
            audioOut.write(b.array());
            audioBytes += b.capacity();
        } catch (IOException e) {
            throw new IllegalStateException("écriture audio : " + e.getMessage());
        }
    }

    @Override
    public long close(double videoTimeScale) {
        if (process == null) return 0;
        try {
            stdin.close();
            int rc = process.waitFor();
            if (rc != 0) throw new IllegalStateException("ffmpeg (vidéo) a terminé avec le code " + rc);
            if (audioOut != null) audioOut.close();
            mux(videoTimeScale);
            return Files.size(output);
        } catch (IOException e) {
            throw new IllegalStateException("finalisation ffmpeg : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompu");
        } finally {
            process = null;
            try { Files.deleteIfExists(videoTmp); Files.deleteIfExists(audioTmp); } catch (IOException ignored) {}
        }
    }

    /** Assemble vidéo (copie, horodatages recalés) et son (AAC) dans le fichier final. */
    private void mux(double videoTimeScale) throws IOException, InterruptedException {
        boolean rescale = Math.abs(videoTimeScale - 1.0) > SCALE_TOLERANCE;
        boolean withAudio = audioBytes > 0;
        if (!rescale && !withAudio) {
            Files.move(videoTmp, output, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        List<String> args = new ArrayList<>();
        if (rescale) args.addAll(List.of("-itsscale", String.format(java.util.Locale.ROOT, "%.6f", videoTimeScale)));
        args.addAll(List.of("-i", videoTmp.toString()));
        if (withAudio) {
            args.addAll(List.of("-f", "s16le", "-ar", Integer.toString(audioHz), "-ac", "2", "-i", audioTmp.toString(),
                    "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-shortest"));
        }
        args.addAll(List.of("-c:v", "copy", "-movflags", "+faststart", output.toString()));
        int rc = ffmpeg(args).waitFor();
        if (rc != 0) throw new IllegalStateException("ffmpeg (mux) a terminé avec le code " + rc);
    }
}
