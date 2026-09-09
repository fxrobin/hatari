package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugToolsTest {

    FakeMachine fake;
    ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        fake = new FakeMachine();
        catalog = new ToolCatalog(new EmulatorSession(fake), McpJson.defaultMapper());
    }

    /** Appelle un outil du catalogue et retourne le résultat brut (comme ToolCatalogTest.call). */
    static CallToolResult rawCall(ToolCatalog catalog, String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(null, new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String name, Map<String, Object> args) {
        CallToolResult r = rawCall(catalog, name, args);
        assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
        return (Map<String, Object>) r.structuredContent();
    }

    @Test
    void setBreakpointSendsBreakcondExpression() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> out = call("set_breakpoint", Map.of("pc", "E00D98"));
        assertEquals(1, out.get("id"));
        assertEquals("pc = $E00D98", out.get("expression"));
        assertEquals("dbg b pc = $E00D98", fake.calls.get(0));
    }

    @Test
    void setWatchpointUsesValueChangedExpression() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> out = call("set_watchpoint", Map.of("addr", "004000", "len", 2, "label", "score"));
        assertEquals("($4000).w ! ($4000).w", out.get("expression"));
        assertEquals("score", out.get("label"));
    }

    @Test
    void clearBreakpointFindsPositionInListing() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_breakpoint", Map.of("pc", "E00D98"));
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_breakpoint", Map.of("pc", "E01000"));
        fake.calls.clear();
        fake.debugOutput = "1 CPU breakpoints:\n  1: pc = $E00D98\n  2: pc = $E01000\n";
        Map<String, Object> out = call("clear_breakpoint", Map.of("id", 2));
        assertEquals(true, out.get("ok"));
        assertEquals("dbg b", fake.calls.get(0));
        assertEquals("dbg b 2", fake.calls.get(1));
    }

    @Test
    void clearAllRemovesOnlyTheRequestedKindWithoutRenumberingTheOther() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_breakpoint", Map.of("pc", "E00D98")); // bp id 1
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        call("set_watchpoint", Map.of("addr", "004000", "len", 2)); // wp id 2
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> wp2 = call("set_watchpoint", Map.of("addr", "005000", "len", 2)); // wp id 3
        fake.calls.clear();

        Map<String, Object> out = call("clear_breakpoint", Map.of("all", true));
        assertEquals(true, out.get("ok"));
        assertEquals(1, out.get("cleared"));
        assertFalse(fake.calls.contains("dbg b all"), "« b all » retirerait aussi les watchpoints côté Hatari");
        assertTrue(fake.calls.contains("dbg b"), "doit relister avant de retirer");

        // Le watchpoint id 3 doit rester retirable avec son id inchangé (pas de renumérotation).
        Map<String, Object> cleared = call("clear_watchpoint", Map.of("id", wp2.get("id")));
        assertEquals(true, cleared.get("ok"));
    }

    @Test
    void sequentialRemovalSearchesFreshListingEachTime() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> bp1 = call("set_breakpoint", Map.of("pc", "E00D98"));
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> bp2 = call("set_breakpoint", Map.of("pc", "E01000"));
        fake.calls.clear();

        call("clear_breakpoint", Map.of("id", bp1.get("id")));
        assertEquals("dbg b", fake.calls.get(0));
        assertEquals("dbg b 1", fake.calls.get(1));

        fake.calls.clear();
        call("clear_breakpoint", Map.of("id", bp2.get("id")));
        assertEquals("dbg b", fake.calls.get(0));
        // FakeMachine ne renumérote pas après une suppression : la position de bp2 reste 2,
        // jamais déduite d'un cache posé au moment de l'ajout.
        assertEquals("dbg b 2", fake.calls.get(1));
    }

    @Test
    void clearBreakpointFailsForUnknownId() {
        CallToolResult r = rawCall(catalog, "clear_breakpoint", Map.of("id", 42));
        assertEquals(Boolean.TRUE, r.isError());
    }

    @Test
    void clearBreakpointFailsForWatchpointId() {
        fake.debugOutput = "CPU condition breakpoint 1 added.\n";
        Map<String, Object> wp = call("set_watchpoint", Map.of("addr", "004000"));
        CallToolResult r = rawCall(catalog, "clear_breakpoint", Map.of("id", wp.get("id")));
        assertEquals(Boolean.TRUE, r.isError());
    }

    @Test
    void debugCommandPassthrough() {
        fake.debugOutput = "hello\n";
        Map<String, Object> out = call("debug_command", Map.of("cmd", "r"));
        assertEquals("hello\n", out.get("output"));
    }

    @Test
    void setBreakpointFailsIfHatariRefuses() {
        fake.debugOutput = "ERROR: invalid expression\n";
        CallToolResult r = rawCall(catalog, "set_breakpoint", Map.of("pc", "E00D98"));
        assertEquals(Boolean.TRUE, r.isError());
    }
}
