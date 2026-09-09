package fr.hatari.mcp;

import fr.hatari.mcp.tools.DebugTools;
import fr.hatari.mcp.tools.MachineTools;
import fr.hatari.mcp.tools.MemoryTools;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;

/** Source unique des outils MCP exposant Hatari. */
public final class ToolCatalog {

    private final MachineTools machineTools;
    private final MemoryTools memoryTools;
    private final DebugTools debugTools;

    public ToolCatalog(EmulatorSession session, McpJsonMapper mapper) {
        Tools tools = new Tools(mapper);
        this.machineTools = new MachineTools(session, tools);
        this.memoryTools = new MemoryTools(session, tools);
        this.debugTools = new DebugTools(session, tools);
    }

    public List<SyncToolSpecification> all() {
        List<SyncToolSpecification> list = new ArrayList<>();
        list.addAll(machineTools.specs());
        list.addAll(memoryTools.specs());
        list.addAll(debugTools.specs());
        return list;
    }
}
