package fr.hatari.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.function.Function;

/** Fabrique d'outils MCP synchrones à partir d'un handler {@code Args -> objet structuré}. */
public final class Tools {

    /**
     * Valeur de retour d'un handler qui, en plus du contenu structuré, contribue des
     * contenus MCP supplémentaires (image, ressource…). Un handler qui n'en a pas besoin
     * retourne directement son objet structuré.
     *
     * @param structured objet sérialisé en JSON structuré, comme un retour ordinaire
     * @param extra      contenus ajoutés au résultat après le contenu textuel
     */
    public record Result(Object structured, List<Content> extra) {}

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
            Object returned = handler.apply(new Args(arguments));
            Object structured = returned;
            List<Content> extra = List.of();
            if (returned instanceof Result r) {
                structured = r.structured();
                extra = r.extra();
            }
            CallToolResult.Builder builder = CallToolResult.builder()
                    .structuredContent(structured)
                    .addTextContent(mapper.writeValueAsString(structured));
            for (Content c : extra) {
                builder.addContent(c);
            }
            return builder.build();
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            return CallToolResult.builder()
                    .isError(true)
                    .addTextContent(msg)
                    .build();
        }
    }
}
