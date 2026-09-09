package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disquettes : mount_disk, eject_disk, boot_disk. */
public final class DiskTools {

    private final EmulatorSession session;
    private final Tools tools;

    public DiskTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(mountDisk(), ejectDisk(), bootDisk());
    }

    private static String expand(String path) {
        return path.replaceFirst("^~", System.getProperty("user.home"));
    }

    private SyncToolSpecification mountDisk() {
        return tools.tool("mount_disk", "Insère une image disquette (.st, .msa, .dim, .stx, .zip...) dans le lecteur (0=A, 1=B).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"drive\":{\"type\":\"integer\",\"enum\":[0,1]}}}",
                args -> {
                    String path = expand(args.str("path"));
                    int drive = args.intVal("drive", 0);
                    session.mutate(m -> m.insertDisk(drive, path));
                    return Map.of("ok", true, "path", path, "drive", drive);
                });
    }

    private SyncToolSpecification ejectDisk() {
        return tools.tool("eject_disk", "Éjecte la disquette du lecteur (0=A par défaut).",
                "{\"type\":\"object\",\"properties\":{\"drive\":{\"type\":\"integer\",\"enum\":[0,1]}}}",
                args -> {
                    int drive = args.intVal("drive", 0);
                    session.mutate(m -> m.ejectDisk(drive));
                    return Map.of("ok", true, "drive", drive);
                });
    }

    private SyncToolSpecification bootDisk() {
        return tools.tool("boot_disk", "Insère l'image en A:, reset à froid, exécute frames trames (défaut 300).",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"frames\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}}}",
                args -> {
                    String path = expand(args.str("path"));
                    int frames = args.intVal("frames", 300);
                    return session.read(m -> {
                        m.insertDisk(0, path);
                        m.reset(true);
                        Map<String, Object> out = new LinkedHashMap<>(MachineTools.runReport(m, m.run(frames)));
                        out.put("path", path);
                        return out;
                    });
                });
    }
}
