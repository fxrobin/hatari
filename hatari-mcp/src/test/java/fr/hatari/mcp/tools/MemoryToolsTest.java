package fr.hatari.mcp.tools;

import fr.hatari.mcp.*;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryToolsTest {

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
    void readMemoryHexdumpsWithAscii() {
        fake.ram[0x2000] = 'H'; fake.ram[0x2001] = 'i';
        Map<String, Object> out = callTool("read_memory", Map.of("addr", "2000", "len", 4));
        assertEquals("002000", out.get("addr"));
        assertEquals("48690000", out.get("bytes"));
        assertTrue(((String) out.get("hexdump")).startsWith("002000: 48 69 00 00"));
        assertTrue(((String) out.get("hexdump")).contains("Hi.."));
    }

    @Test
    void readMemoryRefusesHugeLength() {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals("read_memory")) {
                CallToolResult r = spec.callHandler().apply(null, new CallToolRequest("read_memory", Map.of("addr", "0", "len", 70000)));
                assertEquals(Boolean.TRUE, r.isError());
                return;
            }
        }
        throw new AssertionError("outil absent : read_memory");
    }

    @Test
    void writeMemoryAcceptsHexList() {
        callTool("write_memory", Map.of("addr", "3000", "bytes", List.of("DE", "AD")));
        assertEquals((byte) 0xDE, fake.ram[0x3000]);
        assertEquals((byte) 0xAD, fake.ram[0x3001]);
    }

    @Test
    void readRegistersDecodesFlags() {
        fake.regs[17] = 0x2704;
        Map<String, Object> out = callTool("read_registers", Map.of());
        assertEquals("2704", out.get("sr"));
        @SuppressWarnings("unchecked")
        Map<String, Object> flags = (Map<String, Object>) out.get("flags");
        assertEquals(true, flags.get("s"));
        assertEquals(7, flags.get("ipl"));
        assertEquals(true, flags.get("z"));
    }

    @Test
    void setRegisterDelegates() {
        callTool("set_register", Map.of("name", "d0", "value", "12345678"));
        assertEquals("reg d0=305419896", fake.calls.get(0));
    }

    @Test
    void disassembleDefaultsToPc() {
        fake.regs[16] = 0x1100;
        Map<String, Object> out = callTool("disassemble", Map.of());
        assertEquals("001100", out.get("addr"));
        assertTrue(((String) out.get("text")).contains("jmp"));
    }
}
