package fr.hatari.mcp.keymap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Table clavier Atari ST (disposition US, celle d'EmuTOS par défaut). */
public final class StScancodes {

    public record Key(int scancode, boolean shift) {}

    private static final Map<Character, Key> CHARS = new HashMap<>();
    private static final Map<String, Key> NAMES = new HashMap<>();

    static {
        String unshifted = "1234567890-=";
        String shifted = "!@#$%^&*()_+";
        for (int i = 0; i < unshifted.length(); i++) {
            CHARS.put(unshifted.charAt(i), new Key(0x02 + i, false));
            CHARS.put(shifted.charAt(i), new Key(0x02 + i, true));
        }
        row("qwertyuiop[]", 0x10);
        row("asdfghjkl;'`", 0x1E);
        row("\\zxcvbnm,./", 0x2B);
        String punctUnshifted = "[];'`\\,./";
        String punctShifted = "{}:\"~|<>?";
        for (int i = 0; i < punctUnshifted.length(); i++) {
            Key base = CHARS.get(punctUnshifted.charAt(i));
            CHARS.put(punctShifted.charAt(i), new Key(base.scancode(), true));
        }
        CHARS.put(' ', new Key(0x39, false));
        CHARS.put('\n', new Key(0x1C, false));
        CHARS.put('\t', new Key(0x0F, false));
        CHARS.put('\b', new Key(0x0E, false));
        CHARS.put((char) 27, new Key(0x01, false));

        name("ESC", 0x01); name("BACKSPACE", 0x0E); name("TAB", 0x0F); name("RETURN", 0x1C);
        name("CONTROL", 0x1D); name("SHIFT", 0x2A); name("ALT", 0x38);
        name("SPACE", 0x39); name("CAPSLOCK", 0x3A); name("HOME", 0x47); name("UP", 0x48);
        name("LEFT", 0x4B); name("RIGHT", 0x4D); name("DOWN", 0x50); name("INSERT", 0x52);
        name("DELETE", 0x53); name("UNDO", 0x61); name("HELP", 0x62);
        for (int i = 1; i <= 10; i++) name("F" + i, 0x3A + i);
    }

    private StScancodes() {}

    private static void row(String chars, int first) {
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            CHARS.put(c, new Key(first + i, false));
            if (Character.isLetter(c)) CHARS.put(Character.toUpperCase(c), new Key(first + i, true));
        }
    }

    private static void name(String n, int scancode) {
        NAMES.put(n, new Key(scancode, false));
    }

    public static Optional<Key> ofChar(char c) {
        return Optional.ofNullable(CHARS.get(c));
    }

    /** Nom de touche (insensible à la casse) ou caractère unique. */
    public static Optional<Key> ofName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        Key k = NAMES.get(name.toUpperCase(Locale.ROOT));
        if (k != null) return Optional.of(k);
        if (name.length() == 1) return ofChar(name.charAt(0));
        return Optional.empty();
    }
}
