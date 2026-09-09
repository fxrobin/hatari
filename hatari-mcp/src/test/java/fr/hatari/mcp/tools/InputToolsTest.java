package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputToolsTest {

    static CallToolResult call(ToolCatalog catalog, String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(null, new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @Test
    void pressKeyPressesRunsReleasesRuns() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = call(catalog, "press_key", Map.of("key", "RETURN", "hold_frames", 3));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of("key 28 true", "run 3", "key 28 false", "run 1"), fake.calls);
    }

    @Test
    void typeKeysHandlesShiftedCharacters() {
        FakeMachine fake = new FakeMachine();
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        var r = call(catalog, "type_keys", Map.of("text", "A\n"));
        assertNotEquals(Boolean.TRUE, r.isError());
        assertEquals(List.of(
                "key 42 true", "key 30 true", "run 2", "key 30 false", "key 42 false", "run 1",
                "key 28 true", "run 2", "key 28 false", "run 1"), fake.calls);
    }
}
