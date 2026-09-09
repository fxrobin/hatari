package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capture d'écran. */
public final class VideoTools {

    /** Type MIME du contenu image retourné dans le résultat MCP. */
    private static final String PNG_MIME = "image/png";

    private final EmulatorSession session;
    private final Tools tools;

    public VideoTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(screenshot());
    }

    private SyncToolSpecification screenshot() {
        return tools.tool("screenshot",
                "Capture l'écran émulé en PNG. L'image est retournée directement dans le résultat "
                        + "et écrite dans path (défaut : fichier temporaire).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                args -> {
                    String path = args.str("path", System.getProperty("java.io.tmpdir") + "/hatari-mcp-screenshot.png");
                    Machine.Frame f = session.read(Machine::frame);
                    BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, f.width(), f.height(), f.pixels(), 0, f.width());
                    byte[] png;
                    try {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        ImageIO.write(img, "png", buf);
                        png = buf.toByteArray();
                        Files.write(Path.of(path), png);
                    } catch (IOException e) {
                        throw new IllegalStateException("écriture PNG impossible : " + e.getMessage());
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", path);
                    out.put("width", f.width());
                    out.put("height", f.height());
                    ImageContent image = new ImageContent(null, Base64.getEncoder().encodeToString(png), PNG_MIME);
                    return new Tools.Result(out, List.of(image));
                });
    }
}
