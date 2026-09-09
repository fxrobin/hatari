package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
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

    @SuppressWarnings("unchecked")
    Map<String, Object> callTool(String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                CallToolResult r = spec.callHandler().apply(null, new CallToolRequest(name, args));
                assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
                return (Map<String, Object>) r.structuredContent();
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @Test
    void screenshotWritesPngOfFrame(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("shot.png");
        var r = callTool("screenshot", Map.of("path", png.toString()));
        assertNotEquals(Boolean.TRUE, r.isEmpty());
        BufferedImage img = ImageIO.read(png.toFile());
        assertEquals(320, img.getWidth());
        assertEquals(200, img.getHeight());
    }
}
