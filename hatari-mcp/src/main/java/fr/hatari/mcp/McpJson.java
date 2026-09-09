package fr.hatari.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fabrique du {@link McpJsonMapper} par défaut (Jackson 3). */
public final class McpJson {

    private McpJson() {}

    /** Un mapper neuf, prêt pour le transport et la construction des schémas d'outils. */
    public static McpJsonMapper defaultMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }
}
