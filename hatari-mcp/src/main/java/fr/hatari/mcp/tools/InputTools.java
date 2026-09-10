package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import fr.hatari.mcp.keymap.StScancodes;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Clavier et joystick : press_key, type_keys, set_joystick, press_joystick. */
public final class InputTools {

    private static final int SHIFT = 0x2A;

    private final EmulatorSession session;
    private final Tools tools;

    public InputTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(pressKey(), typeKeys(), setJoystick(), pressJoystick());
    }

    /** Appuie (avec Shift si besoin), tient hold trames, relâche, laisse gap trames. */
    public static void tap(Machine m, StScancodes.Key k, int hold, int gap) {
        if (k.shift()) m.key(SHIFT, true);
        m.key(k.scancode(), true);
        m.run(hold);
        m.key(k.scancode(), false);
        if (k.shift()) m.key(SHIFT, false);
        m.run(gap);
    }

    private SyncToolSpecification pressKey() {
        return tools.tool("press_key",
                "Appuie puis relâche une touche : nom (RETURN, ESC, SPACE, F1..F10, UP, DOWN, LEFT, RIGHT, "
                        + "TAB, BACKSPACE, DELETE, INSERT, HOME, HELP, UNDO, CONTROL, SHIFT, ALT, CAPSLOCK) "
                        + "ou caractère ASCII. hold_frames : durée d'appui (défaut 2).",
                "{\"type\":\"object\",\"required\":[\"key\"],\"properties\":{\"key\":{\"type\":\"string\"},"
                        + "\"hold_frames\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}}}",
                args -> {
                    String name = args.str("key");
                    StScancodes.Key k = StScancodes.ofName(name)
                            .orElseThrow(() -> new IllegalArgumentException("touche inconnue : " + name));
                    int hold = args.intVal("hold_frames", 2);
                    session.mutate(m -> tap(m, k, hold, 1));
                    return Map.of("ok", true, "key", name, "scancode", String.format("%02X", k.scancode()));
                });
    }

    private SyncToolSpecification typeKeys() {
        return tools.tool("type_keys",
                "Tape une chaîne ASCII (\\n = RETURN). hold_frames par touche (défaut 2), gap_frames entre touches (défaut 1).",
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"hold_frames\":{\"type\":\"integer\",\"minimum\":1},\"gap_frames\":{\"type\":\"integer\",\"minimum\":0}}}",
                args -> {
                    String text = args.str("text");
                    int hold = args.intVal("hold_frames", 2);
                    int gap = args.intVal("gap_frames", 1);

                    List<Character> unmapped = new ArrayList<>();
                    for (char c : text.toCharArray()) {
                        if (StScancodes.ofChar(c).isEmpty()) unmapped.add(c);
                    }
                    if (!unmapped.isEmpty()) {
                        throw new IllegalArgumentException("caractères non mappés : " + unmapped);
                    }

                    session.mutate(m -> {
                        for (char c : text.toCharArray()) {
                            StScancodes.Key k = StScancodes.ofChar(c).orElseThrow();
                            tap(m, k, hold, gap);
                        }
                    });
                    return Map.of("ok", true, "typed", text.length());
                });
    }

    private static final String JOY_PROPS =
            "\"port\":{\"type\":\"integer\",\"enum\":[0,1]},"
            + "\"up\":{\"type\":\"boolean\"},\"down\":{\"type\":\"boolean\"},"
            + "\"left\":{\"type\":\"boolean\"},\"right\":{\"type\":\"boolean\"},"
            + "\"fire\":{\"type\":\"boolean\"}";

    /** Masque JOY_* depuis les booleens de l'appel. */
    static int joyMask(fr.hatari.mcp.Args args) {
        int m = 0;
        if (args.bool("up", false)) m |= Machine.JOY_UP;
        if (args.bool("down", false)) m |= Machine.JOY_DOWN;
        if (args.bool("left", false)) m |= Machine.JOY_LEFT;
        if (args.bool("right", false)) m |= Machine.JOY_RIGHT;
        if (args.bool("fire", false)) m |= Machine.JOY_FIRE;
        return m;
    }

    private static int joyPort(fr.hatari.mcp.Args args) {
        int port = args.intVal("port", 1);
        if (port < 0 || port > 1) throw new IllegalArgumentException("port joystick invalide : " + port);
        return port;
    }

    private SyncToolSpecification setJoystick() {
        return tools.tool("set_joystick",
                "Fixe l'état du joystick (port 1 = port jeu par défaut) : up/down/left/right/fire maintenus "
                        + "jusqu'au prochain appel. Sans argument : tout relâché.",
                "{\"type\":\"object\",\"properties\":{" + JOY_PROPS + "}}",
                args -> {
                    int port = joyPort(args);
                    int mask = joyMask(args);
                    session.mutate(m -> m.joystick(port, mask));
                    return Map.of("ok", true, "port", port, "mask", String.format("%02X", mask));
                });
    }

    private SyncToolSpecification pressJoystick() {
        return tools.tool("press_joystick",
                "Impulsion joystick : applique up/down/left/right/fire pendant frames trames (défaut 2), "
                        + "relâche tout, puis 1 trame.",
                "{\"type\":\"object\",\"properties\":{" + JOY_PROPS
                        + ",\"frames\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500}}}",
                args -> {
                    int port = joyPort(args);
                    int mask = joyMask(args);
                    int frames = args.intVal("frames", 2);
                    session.mutate(m -> {
                        m.joystick(port, mask);
                        m.run(frames);
                        m.joystick(port, 0);
                        m.run(1);
                    });
                    return Map.of("ok", true, "port", port, "mask", String.format("%02X", mask), "frames", frames);
                });
    }
}
