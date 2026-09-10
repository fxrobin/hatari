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
        FfmpegSink sink = new FfmpegSink(out, 25, 1);
        int[] px = new int[320 * 200];
        for (int i = 0; i < 10; i++) {
            java.util.Arrays.fill(px, i * 0x101010);
            sink.accept(new Machine.Frame(320, 200, px));
        }
        long bytes = sink.close();
        assertTrue(bytes > 0);
        assertEquals(bytes, Files.size(out));
        byte[] head = Files.readAllBytes(out);
        assertEquals("ftyp", new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
        Files.delete(out);
    }
}
