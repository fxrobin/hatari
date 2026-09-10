package fr.hatari.mcp.video;

import fr.hatari.mcp.Machine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Ne tourne que si ffmpeg est sur le PATH. */
public class FfmpegSinkTest {

    public static boolean ffmpegAvailable() {
        try { FfmpegSink.requireFfmpeg(); return true; } catch (RuntimeException e) { return false; }
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void encodesAFewFramesToMp4() throws Exception {
        Path out = Files.createTempFile("hatari-mcp-sink", ".mp4");
        FfmpegSink sink = new FfmpegSink(out, 25, 1, 0);
        int[] px = new int[320 * 200];
        for (int i = 0; i < 10; i++) {
            java.util.Arrays.fill(px, i * 0x101010);
            sink.accept(new Machine.Frame(320, 200, px));
        }
        long bytes = sink.close(1.0);
        assertTrue(bytes > 0);
        assertEquals(bytes, Files.size(out));
        byte[] head = Files.readAllBytes(out);
        assertEquals("ftyp", new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
        Files.delete(out);
    }

    /** Sortie de ffprobe sur le fichier : codecs et durée. */
    static String probe(Path f) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                "stream=codec_type,codec_name:format=duration", "-of", "default=nw=1", f.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    @Test
    @EnabledIf("ffmpegAvailable")
    void muxesAudioAndRescalesVideo() throws Exception {
        Path out = Files.createTempFile("hatari-mcp-sink", ".mp4");
        FfmpegSink sink = new FfmpegSink(out, 25, 1, 44100);
        int[] px = new int[320 * 200];
        short[] pcm = new short[882 * 2];
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = pcm[i + 1] = (short) (8000 * Math.sin(i * 2 * Math.PI * 440 / 44100));
        }
        for (int i = 0; i < 50; i++) {                // 2 s nominales de vidéo
            java.util.Arrays.fill(px, i * 0x050505);
            sink.accept(new Machine.Frame(320, 200, px));
            sink.audio(pcm); sink.audio(pcm);         // 2 trames émulées de son par image
        }
        long bytes = sink.close(1.5);                 // vidéo étirée à 3 s
        assertTrue(bytes > 0);
        String info = probe(out);
        assertTrue(info.contains("codec_type=audio"), info);
        assertTrue(info.contains("codec_name=aac"), info);
        assertTrue(info.contains("codec_name=h264"), info);
        // durée ≈ 2 s (audio, -shortest) : la vidéo étirée à 3 s est coupée à la fin du son
        double dur = Double.parseDouble(info.replaceAll("(?s).*duration=([0-9.]+).*", "$1"));
        assertTrue(dur > 1.8 && dur < 3.2, "duration=" + dur);
        assertFalse(Files.exists(out.resolveSibling(out.getFileName() + ".video.tmp.mp4")));
        assertFalse(Files.exists(out.resolveSibling(out.getFileName() + ".audio.s16le")));
        Files.delete(out);
    }
}
