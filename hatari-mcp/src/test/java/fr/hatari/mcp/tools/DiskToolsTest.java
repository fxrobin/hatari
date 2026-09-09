package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiskToolsTest {

    @SuppressWarnings("unchecked")
    Map<String, Object> callTool(ToolCatalog catalog, String name, Map<String, Object> args) {
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
    void mountDiskInsertsInDriveA() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = callTool(catalog, "mount_disk", Map.of("path", "/tmp/x.st"));
        assertEquals(List.of("insert 0 /tmp/x.st"), fake.calls);
    }

    @Test
    void bootDiskMountsResetsAndRuns() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = callTool(catalog, "boot_disk", Map.of("path", "/tmp/x.st", "frames", 100));
        assertEquals(List.of("insert 0 /tmp/x.st", "reset true", "run 100"), fake.calls);
    }
}
