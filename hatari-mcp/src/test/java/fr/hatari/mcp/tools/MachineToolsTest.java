package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;

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
        Map<String, Object> out = callTool("reset", Map.of());
        assertEquals("reset true", fake.calls.get(0));
        assertEquals(true, out.get("cold"));
    }

    @Test
    void machineStateExposesCountersAndRegisters() {
        fake.vbl = 7;
        Map<String, Object> out = callTool("machine_state", Map.of());
        assertEquals(7L, ((Number) out.get("vbl")).longValue());
        assertTrue(out.containsKey("registers"));
    }

    @Test
    void runUntilPcCleansUpStaleBreakpointOnTimeout() {
        fake.nextStop = Machine.StopReason.NONE; // Timeout (not reached)
        Map<String, Object> out = callTool("run_until_pc", Map.of("pc", "E00D98", "max_frames", 50));
        assertEquals("NONE", out.get("reason"));
        assertEquals(false, out.get("reached"));
        // Should set breakpoint, run, list breakpoints, remove breakpoint
        assertEquals("dbg b pc = $E00D98 :once", fake.calls.get(0));
        assertEquals("run 50", fake.calls.get(1));
        assertEquals("dbg b", fake.calls.get(2));
        assertEquals("dbg b 1", fake.calls.get(3)); // Remove position 1
    }

    @Test
    void breakpointPositionFindsExpressionInListing() {
        String listing = "2 conditional CPU breakpoints:\n" +
                "   1:\tpc = $e00d98 :once\n" +
                "   2:\ta0 = $1000\n";
        OptionalInt pos = MachineTools.breakpointPosition(listing, "pc = $E00D98");
        assertTrue(pos.isPresent());
        assertEquals(1, pos.getAsInt());
    }

    @Test
    void breakpointPositionHandlesCaseInsensitive() {
        String listing = "1 conditional CPU breakpoints:\n" +
                "   5:\tPC = $E00D98 :ONCE\n";
        OptionalInt pos = MachineTools.breakpointPosition(listing, "pc = $e00d98");
        assertTrue(pos.isPresent());
        assertEquals(5, pos.getAsInt());
    }

    @Test
    void breakpointPositionNotFound() {
        String listing = "1 conditional CPU breakpoints:\n" +
                "   1:\tpc = $e00d98\n";
        OptionalInt pos = MachineTools.breakpointPosition(listing, "pc = $F00000");
        assertTrue(pos.isEmpty());
    }

    @Test
    void breakpointPositionEmptyListing() {
        String listing = "0 conditional CPU breakpoints:\n";
        OptionalInt pos = MachineTools.breakpointPosition(listing, "pc = $E00D98");
        assertTrue(pos.isEmpty());
    }
}
