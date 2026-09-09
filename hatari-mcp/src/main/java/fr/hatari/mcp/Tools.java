package fr.hatari.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.function.Function;

/** Fabrique d'outils MCP synchrones à partir d'un handler {@code Args -> objet structuré}. */
public final class Tools {

    private final McpJsonMapper mapper;

    public Tools(McpJsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param name          identifiant de l'outil (snake_case)
     * @param description    description destinée à l'agent
     * @param inputSchemaJson JSON Schema de l'objet d'entrée (validé par le SDK)
     * @param handler        logique métier ; retourne un objet sérialisé en JSON structuré
     */
    public SyncToolSpecification tool(String name, String description, String inputSchemaJson,
                                      Function<Args, Object> handler) {
        Tool tool = Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(mapper, inputSchemaJson)
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(handler, request.arguments()))
                .build();
    }

    private CallToolResult invoke(Function<Args, Object> handler, java.util.Map<String, Object> arguments) {
        try {
            Object result = handler.apply(new Args(arguments));
            return CallToolResult.builder()
                    .structuredContent(result)
                    .addTextContent(mapper.writeValueAsString(result))
                    .build();
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(msg)
                    .build();
        }
    }
}
