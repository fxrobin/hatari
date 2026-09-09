package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.List;
import java.util.Map;

/** Outils de contrôle de la machine : ping, état, reset, exécution. */
public final class MachineTools {

    private final EmulatorSession session;
    private final Tools tools;

    public MachineTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(ping());
    }

    private SyncToolSpecification ping() {
        return tools.tool("ping", "Vérifie que le serveur et la machine émulée répondent.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Map.of("ok", true, "vbl", m.vblCount())));
    }
}
