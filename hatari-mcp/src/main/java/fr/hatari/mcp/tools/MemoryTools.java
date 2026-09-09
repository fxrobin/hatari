package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mémoire, registres, désassemblage. */
public final class MemoryTools {

    static final int MAX_READ = 65536;

    private final EmulatorSession session;
    private final Tools tools;

    public MemoryTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(readMemory(), writeMemory(), readRegisters(), setRegister(), disassemble());
    }

    private SyncToolSpecification readMemory() {
        return tools.tool("read_memory",
                "Lit len octets (défaut 64, max 65536) à l'adresse hex 24 bits. Lire la zone IO "
                        + "($FF8000+) a les mêmes effets de bord qu'un accès CPU.",
                "{\"type\":\"object\",\"required\":[\"addr\"],\"properties\":{"
                        + "\"addr\":{\"type\":\"string\"},\"len\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":65536}}}",
                args -> {
                    int addr = args.hex("addr");
                    int len = args.intVal("len", 64);
                    if (len < 1 || len > MAX_READ) throw new IllegalArgumentException("len doit être entre 1 et " + MAX_READ);
                    byte[] data = session.read(m -> m.readMemory(addr, len));
                    StringBuilder hex = new StringBuilder(len * 2);
                    for (byte b : data) hex.append(Fmt.hex8(b));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("addr", Fmt.hex24(addr));
                    out.put("len", len);
                    out.put("bytes", hex.toString());
                    out.put("hexdump", Fmt.hexdump(addr, data));
                    return out;
                });
    }

    private SyncToolSpecification writeMemory() {
        return tools.tool("write_memory",
                "Écrit une liste d'octets hex (\"DE\",\"AD\") à l'adresse hex donnée.",
                "{\"type\":\"object\",\"required\":[\"addr\",\"bytes\"],\"properties\":{"
                        + "\"addr\":{\"type\":\"string\"},\"bytes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
                args -> {
                    int addr = args.hex("addr");
                    List<Integer> list = args.byteList("bytes");
                    byte[] data = new byte[list.size()];
                    for (int i = 0; i < data.length; i++) data[i] = (byte) (int) list.get(i);
                    session.mutate(m -> m.writeMemory(addr, data));
                    return Map.of("ok", true, "addr", Fmt.hex24(addr), "len", data.length);
                });
    }

    private SyncToolSpecification readRegisters() {
        return tools.tool("read_registers", "Registres 68000 (D0-D7, A0-A7, PC, SR, USP, ISP) et drapeaux.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Fmt.registers(m.registers())));
    }

    private SyncToolSpecification setRegister() {
        return tools.tool("set_register", "Écrit un registre (pc, sr, d0-d7, a0-a7, usp, isp) ; value en hex.",
                "{\"type\":\"object\",\"required\":[\"name\",\"value\"],\"properties\":{"
                        + "\"name\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}",
                args -> {
                    String name = args.str("name");
                    long value = Long.parseLong(stripHex(args.str("value")), 16);
                    session.mutate(m -> m.setRegister(name, value));
                    return Map.of("ok", true, "name", name, "value", Fmt.hex32(value));
                });
    }

    private SyncToolSpecification disassemble() {
        return tools.tool("disassemble", "Désassemble count instructions (défaut 16) à partir de addr (défaut PC).",
                "{\"type\":\"object\",\"properties\":{\"addr\":{\"type\":\"string\"},"
                        + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":256}}}",
                args -> session.read(m -> {
                    int addr = args.has("addr") ? args.hex("addr") : (int) m.registers().pc();
                    int count = args.intVal("count", 16);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("addr", Fmt.hex24(addr));
                    out.put("text", m.disassemble(addr, count));
                    return out;
                }));
    }

    static String stripHex(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) return s.substring(2);
        if (s.startsWith("$")) return s.substring(1);
        return s;
    }
}
