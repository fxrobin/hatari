package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capture d'écran. */
public final class VideoTools {

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
                "Capture l'écran émulé en PNG. path optionnel (défaut : fichier temporaire). Lire ensuite le fichier pour voir l'image.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                args -> {
                    String path = args.str("path", System.getProperty("java.io.tmpdir") + "/hatari-mcp-screenshot.png");
                    Machine.Frame f = session.read(Machine::frame);
                    BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, f.width(), f.height(), f.pixels(), 0, f.width());
                    try {
                        ImageIO.write(img, "png", new File(path));
                    } catch (IOException e) {
                        throw new IllegalStateException("écriture PNG impossible : " + e.getMessage());
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", path);
                    out.put("width", f.width());
                    out.put("height", f.height());
                    return out;
                });
    }
}
