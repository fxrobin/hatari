package fr.hatari.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/** Formatage partagé des sorties d'outils (hex majuscule sans préfixe, registres 68k, hexdump). */
public final class Fmt {

    private Fmt() {}

    public static String hex8(int v) { return String.format("%02X", v & 0xFF); }
    public static String hex16(int v) { return String.format("%04X", v & 0xFFFF); }
    public static String hex24(int v) { return String.format("%06X", v & 0xFFFFFF); }
    public static String hex32(long v) { return String.format("%08X", v & 0xFFFFFFFFL); }

    /** Registres 68000 + drapeaux décodés. */
    public static Map<String, Object> registers(Machine.Registers r) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) out.put("d" + i, hex32(r.d(i)));
        for (int i = 0; i < 8; i++) out.put("a" + i, hex32(r.a(i)));
        out.put("pc", hex24((int) r.pc()));
        out.put("sr", hex16(r.sr()));
        out.put("usp", hex32(r.usp()));
        out.put("isp", hex32(r.isp()));
        out.put("flags", flags(r.sr()));
        return out;
    }

    /** SR décodé : T1, S, IPL, X, N, Z, V, C. */
    public static Map<String, Object> flags(int sr) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("t", (sr & 0x8000) != 0);
        f.put("s", (sr & 0x2000) != 0);
        f.put("ipl", (sr >> 8) & 7);
        f.put("x", (sr & 0x10) != 0);
        f.put("n", (sr & 0x08) != 0);
        f.put("z", (sr & 0x04) != 0);
        f.put("v", (sr & 0x02) != 0);
        f.put("c", (sr & 0x01) != 0);
        return f;
    }

    /** Hexdump 16 octets par ligne : adresse, octets, ASCII. */
    public static String hexdump(int base, byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += 16) {
            sb.append(hex24(base + off)).append(": ");
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (off + i < data.length) {
                    int b = data[off + i] & 0xFF;
                    sb.append(hex8(b)).append(' ');
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }
}
