package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MachineToolsTest {

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
    void runFramesReportsReasonPcAndVbl() {
        fake.regs[16] = 0xE00D98L;
        Map<String, Object> out = callTool("run_frames", Map.of("n", 10));
        assertEquals("NONE", out.get("reason"));
        assertEquals(10, out.get("frames_done"));
        assertEquals("E00D98", out.get("pc"));
        assertEquals(10L, ((Number) out.get("vbl")).longValue());
        assertEquals("run 10", fake.calls.get(0));
    }

    @Test
    void runUntilPcSetsOnceBreakpointThenRuns() {
        fake.nextStop = Machine.StopReason.BREAKPOINT;
        Map<String, Object> out = callTool("run_until_pc", Map.of("pc", "E00D98", "max_frames", 50));
        assertEquals("BREAKPOINT", out.get("reason"));
        assertEquals("dbg b pc = $E00D98 :once", fake.calls.get(0));
        assertEquals("run 50", fake.calls.get(1));
    }

    @Test
    void stepArmsThenRunsOneFrame() {
        Map<String, Object> out = callTool("step", Map.of("n", 3));
        assertEquals("STEPS", out.get("reason"));
        assertEquals("step 3", fake.calls.get(0));
        assertEquals("run 1", fake.calls.get(1));
    }

    @Test
    void resetDefaultsToCold() {
        callTool("reset", Map.of());
        assertEquals("reset true", fake.calls.get(0));
    }

    @Test
    void machineStateExposesCountersAndRegisters() {
        fake.vbl = 7;
        Map<String, Object> out = callTool("machine_state", Map.of());
        assertEquals(7L, ((Number) out.get("vbl")).longValue());
        assertTrue(out.containsKey("registers"));
    }
}
