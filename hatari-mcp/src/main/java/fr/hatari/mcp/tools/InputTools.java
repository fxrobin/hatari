package fr.hatari.mcp.tools;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import fr.hatari.mcp.keymap.StScancodes;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Clavier : press_key, type_keys. */
public final class InputTools {

    private static final int SHIFT = 0x2A;

    private final EmulatorSession session;
    private final Tools tools;

    public InputTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(pressKey(), typeKeys());
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
}
