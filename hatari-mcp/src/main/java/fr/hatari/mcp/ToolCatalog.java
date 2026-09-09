package fr.hatari.mcp;

import fr.hatari.mcp.tools.MachineTools;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;

/** Source unique des outils MCP exposant Hatari. */
public final class ToolCatalog {

    private final MachineTools machineTools;

    public ToolCatalog(EmulatorSession session, McpJsonMapper mapper) {
        Tools tools = new Tools(mapper);
        this.machineTools = new MachineTools(session, tools);
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> list = new ArrayList<>();
        list.addAll(machineTools.specs());
        return list;
    }
}
