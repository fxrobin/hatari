package fr.hatari.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {

    static CallToolResult call(ToolCatalog catalog, String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(null, new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @Test
    void pingReturnsOkAndVbl() {
        FakeMachine fake = new FakeMachine();
        fake.vbl = 42;
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
        CallToolResult res = call(catalog, "ping", Map.of());
        assertNotEquals(Boolean.TRUE, res.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) res.structuredContent();
        assertEquals(true, out.get("ok"));
        assertEquals(42L, ((Number) out.get("vbl")).longValue());
    }

    @Test
    void toolNamesAreUnique() {
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(new FakeMachine()), McpJson.defaultMapper());
        List<String> names = catalog.all().stream().map(s -> s.tool().name()).toList();
        assertEquals(names.size(), names.stream().distinct().count());
    }
}
