package fr.hatari.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accès typé aux arguments d'un appel d'outil, avec parsing hexadécimal tolérant.
 *
 * <p>Les nombres 68000 (adresses, scancodes) sont acceptés en hex ("E7C3", "0xE7C3",
 * "$E7C3") ou en entier JSON. Les valeurs manquantes lèvent une erreur explicite,
 * remontée au client comme résultat d'outil en erreur.
 */
public final class Args {

    private final Map<String, Object> raw;

    public Args(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    public boolean has(String key) {
        return raw.get(key) != null;
    }

    /** Objet imbriqué (ex. {@code start}/{@code stop} d'une capture), ou une map vide si absent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = raw.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Entier 64 bits (tailles de fichier). */
    public long longVal(String key) {
        Object v = require(key);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    /** Entier générique (accepte hex string ou nombre). */
    public int intVal(String key) {
        Object v = require(key);
        return parseInt(v, key);
    }

    public int intVal(String key, int def) {
        Object v = raw.get(key);
        return v == null ? def : parseInt(v, key);
    }

    /**
     * Adresse/valeur hexadécimale 24 bits, TOUJOURS lue en hexadécimal
     * ({@code "6100"} → {@code $6100}) — les adresses 68000 sont hex par
     * convention. Les préfixes {@code 0x}/{@code $} sont tolérés.
     *
     * <p>Un <b>entier JSON nu est refusé</b>. Il l'était auparavant accepté et lu
     * en décimal, ce qui faisait dépendre la base des chiffres de l'adresse :
     * {@code 8310} devenait $2076 en silence, quand {@code "E450"} — qui porte
     * une lettre, donc reste une chaîne — était bien lu en hexadécimal. Dans un
     * outil de débogage, une valeur fausse sans message coûte plus cher qu'un
     * refus.
     */
    public int hex(String key) {
        return hexValue(require(key), key) & 0xFFFFFF;
    }

    private static int hexValue(Object v, String key) {
        if (v instanceof Number n) {
            int d = n.intValue();
            // Les deux lectures possibles, pour que l'appelant tranche lui-même :
            // les chiffres tels quels lus en hexadécimal, ou la valeur décimale.
            throw new IllegalArgumentException(key + " doit être une chaîne hexadécimale, pas un entier JSON :"
                    + " écrivez \"" + d + "\" pour $" + d
                    + ", ou \"" + Integer.toHexString(d).toUpperCase() + "\" si vous vouliez la valeur décimale "
                    + d + ".");
        }
        String s = String.valueOf(v).trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        else if (s.startsWith("$")) s = s.substring(1);
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("adresse hex invalide pour " + key + " : " + s);
        }
    }

    public boolean bool(String key, boolean def) {
        Object v = raw.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public String str(String key, String def) {
        Object v = raw.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public String str(String key) {
        return String.valueOf(require(key));
    }

    /** Liste d'octets depuis un tableau JSON (éléments hex string ou entiers). */
    public List<Integer> byteList(String key) {
        Object v = require(key);
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " doit être un tableau");
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException(key + " ne peut pas être vide");
        }
        List<Integer> out = new ArrayList<>(list.size());
        // Éléments hex par convention (octets/scancodes 68000) : "12" → $12.
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            int val = hexValue(o, key);
            if (val < 0 || val > 0xFF) {
                throw new IllegalArgumentException(key + "[" + i + "]: valeur octet invalide " + String.format("0x%X", val & 0xFFFFFFFFL) + " (doit être 0x00-0xFF)");
            }
            out.add(val);
        }
        return out;
    }

    private Object require(String key) {
        Object v = raw.get(key);
        if (v == null) {
            throw new IllegalArgumentException("argument manquant : " + key);
        }
        return v;
    }

    private static int parseInt(Object v, String key) {
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
            if (s.startsWith("$")) return Integer.parseInt(s.substring(1), 16);
            // Sinon : hex si présence de lettres A-F, décimal autrement.
            // Balayage explicite plutôt que deux regex : « .*[a-fA-F].* » coûte
            // un temps quadratique sur une chaîne longue sans lettre.
            if (isHexDigits(s) && hasHexLetter(s)) {
                return Integer.parseInt(s, 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("valeur numérique invalide pour " + key + " : " + s);
        }
    }

    private static boolean isHexDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static boolean hasHexLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) return true;
        }
        return false;
    }
}
