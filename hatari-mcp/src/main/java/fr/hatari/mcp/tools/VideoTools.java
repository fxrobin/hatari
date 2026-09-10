package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import fr.hatari.mcp.video.FfmpegSink;
import fr.hatari.mcp.video.VideoRecorder;
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

/** Capture d'écran et capture vidéo MP4. */
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
        return List.of(screenshot(), armVideoCapture(), stopVideoCapture(), videoCaptureStatus());
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

    private SyncToolSpecification armVideoCapture() {
        return tools.tool("arm_video_capture",
                "Arme une capture vidéo MP4 (H.264, sans son) : dès lors, tout outil qui fait avancer "
                        + "la machine (run_frames, run_to_breakpoint, press_key, press_joystick, boot_disk…) "
                        + "filme une image toutes les every trames émulées (défaut 2 → 25 img/s = vitesse "
                        + "réelle à 50 Hz). scale : facteur entier d'agrandissement (défaut 2 → 640×400). "
                        + "Une seule capture à la fois : erreur si une capture est en cours, appeler "
                        + "stop_video_capture d'abord. Requiert ffmpeg sur le PATH.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"every\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100},"
                        + "\"scale\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8}}}",
                args -> {
                    String path = args.str("path", System.getProperty("java.io.tmpdir") + "/hatari-mcp-capture.mp4");
                    int every = args.intVal("every", 2);
                    int scale = args.intVal("scale", 2);
                    int fps = Math.max(1, Math.round(50f / every));
                    VideoRecorder rec = session.recorder();
                    if (rec.active()) throw new IllegalStateException(
                            "une capture est déjà en cours : appeler stop_video_capture d'abord");
                    FfmpegSink.requireFfmpeg();
                    rec.arm(new FfmpegSink(Path.of(path), fps, scale), path, every, fps);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("armed", true);
                    out.put("path", path);
                    out.put("every", every);
                    out.put("fps", fps);
                    out.put("scale", scale);
                    return out;
                });
    }

    private SyncToolSpecification stopVideoCapture() {
        return tools.tool("stop_video_capture",
                "Arrête la capture vidéo et finalise le MP4. Idempotent : rappeler après l'arrêt renvoie "
                        + "le même compte rendu (path, frames, seconds, bytes).",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> session.recorder().stop()));
    }

    private SyncToolSpecification videoCaptureStatus() {
        return tools.tool("video_capture_status",
                "État de la capture vidéo : active, path, images filmées, trames émulées, secondes. "
                        + "Erreur si aucune capture n'a été armée.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> session.recorder().status()));
    }
}
