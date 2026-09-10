package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VideoToolsTest {

    FakeMachine fake;
    ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        fake = new FakeMachine();
        catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
    }

    CallToolResult rawCall(String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                CallToolResult r = spec.callHandler().apply(null, new CallToolRequest(name, args));
                assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
                return r;
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> callTool(String name, Map<String, Object> args) {
        return (Map<String, Object>) rawCall(name, args).structuredContent();
    }

    @Test
    void screenshotWritesPngOfFrame(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("shot.png");
        var r = callTool("screenshot", Map.of("path", png.toString()));
        assertNotEquals(Boolean.TRUE, r.isEmpty());
        assertEquals(png.toString(), r.get("path"));
        assertEquals(320, r.get("width"));
        assertEquals(200, r.get("height"));
        BufferedImage img = ImageIO.read(png.toFile());
        assertEquals(320, img.getWidth());
        assertEquals(200, img.getHeight());
    }

    /** Spec §5.2 : le PNG doit aussi revenir en contenu image MCP, pas seulement sur disque. */
    @Test
    void screenshotReturnsPngAsImageContent(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("shot.png");
        CallToolResult r = rawCall("screenshot", Map.of("path", png.toString()));

        ImageContent image = null;
        for (Content c : r.content()) {
            if (c instanceof ImageContent ic) image = ic;
        }
        assertNotNull(image, () -> "pas de contenu image dans " + r.content());
        assertEquals("image/png", image.mimeType());

        byte[] decoded = Base64.getDecoder().decode(image.data());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(decoded));
        assertNotNull(img, "les octets base64 ne sont pas un PNG lisible");
        assertEquals(320, img.getWidth());
        assertEquals(200, img.getHeight());
    }

    @Test
    void videoCaptureStatusBeforeArmIsAnError() {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals("video_capture_status")) {
                CallToolResult r = spec.callHandler().apply(null, new CallToolRequest("video_capture_status", Map.of()));
                assertEquals(Boolean.TRUE, r.isError());
                return;
            }
        }
        throw new AssertionError("outil absent : video_capture_status");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIf("fr.hatari.mcp.video.FfmpegSinkTest#ffmpegAvailable")
    void armRunStopProducesAnMp4(@TempDir Path dir) throws Exception {
        String out = dir.resolve("cap.mp4").toString();
        Map<String, Object> armed = callTool("arm_video_capture", Map.of("path", out, "every", 2, "scale", 1));
        assertEquals(true, armed.get("armed"));
        assertEquals(25, ((Number) armed.get("fps")).intValue());
        rawCall("run_frames", Map.of("n", 20));
        Map<String, Object> st = callTool("video_capture_status", Map.of());
        assertEquals(true, st.get("active"));
        assertEquals(10, ((Number) st.get("frames")).intValue());
        Map<String, Object> stop = callTool("stop_video_capture", Map.of());
        assertEquals(false, stop.get("active"));
        assertTrue(((Number) stop.get("bytes")).longValue() > 0);
        assertTrue(java.nio.file.Files.exists(Path.of(out)));
        // idempotent
        assertEquals(stop, callTool("stop_video_capture", Map.of()));
        // réarmable après arrêt
        callTool("arm_video_capture", Map.of("path", dir.resolve("cap2.mp4").toString()));
        callTool("stop_video_capture", Map.of());
    }
}
