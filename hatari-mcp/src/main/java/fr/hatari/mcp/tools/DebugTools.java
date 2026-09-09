package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Breakpoints, watchpoints, symboles, passerelle brute vers le debugger Hatari. */
public final class DebugTools {

    private record Point(int id, String kind, String expression, String label) {}

    private final EmulatorSession session;
    private final Tools tools;
    private final Map<Integer, Point> points = new LinkedHashMap<>();
    private int nextId = 1;

    public DebugTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(setBreakpoint(), clearBreakpoint(), setWatchpoint(), clearWatchpoint(),
                listBreakpoints(), loadSymbols(), debugCommand());
    }

    /**
     * Envoie « b <expr> », vérifie l'acquittement de Hatari et enregistre le point.
     *
     * <p>L'envoi et la mise à jour de {@code points}/{@code nextId} tiennent dans un seul
     * {@code session.read} : la carte locale et l'état du debugger Hatari sont ainsi
     * modifiés sous le même moniteur, sans fenêtre où deux appels concurrents
     * pourraient s'attribuer le même id ou observer une carte désynchronisée.
     */
    private Point add(String kind, String expression, String label) {
        return session.read(m -> {
            String out = m.debugCommand("b " + expression);
            if (!out.contains("added")) {
                throw new IllegalStateException("Hatari a refusé le breakpoint : " + out.trim());
            }
            Point p = new Point(nextId++, kind, expression, label);
            points.put(p.id(), p);
            return p;
        });
    }

    /**
     * Retire tous les points d'un type, ou un seul par id.
     *
     * <p>{@code all} retire chaque point du type demandé un par un (relisting + recherche par
     * expression avant chaque suppression, comme le cas par id) plutôt que d'envoyer « b all » :
     * « b all » efface aussi les points Hatari de l'autre type, ce qui obligerait à les reposer
     * avec de nouveaux ids Hatari — les ids Java de l'autre type doivent rester stables et
     * retirables après un {@code clear_breakpoint {all}} / {@code clear_watchpoint {all}}.
     *
     * <p>Toute la transaction (lecture du listing, suppressions, mise à jour de {@code points})
     * se fait dans un seul {@code session.read} : la carte locale est modifiée sous le même
     * moniteur que les commandes envoyées au debugger.
     *
     * <p>En mode {@code all}, un point disparu du listing Hatari entre-temps (un « :once » qui a
     * déclenché, par exemple) n'interrompt pas la boucle : il est retiré de la carte locale, son
     * id est reporté dans {@code vanished} et les autres ids sont traités normalement.
     */
    private Map<String, Object> clear(String kind, Args args) {
        boolean all = args.bool("all", false);
        int wanted = all ? -1 : args.intVal("id");
        return session.read(m -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (all) {
                List<Integer> ids = points.values().stream()
                        .filter(p -> p.kind().equals(kind))
                        .map(Point::id)
                        .toList();
                List<Integer> vanished = new ArrayList<>();
                int cleared = 0;
                for (int id : ids) {
                    if (removeOne(m, id, kind, true)) {
                        cleared++;
                    } else {
                        vanished.add(id);
                    }
                }
                out.put("ok", true);
                out.put("cleared", cleared);
                out.put("vanished", vanished);
                return out;
            }
            removeOne(m, wanted, kind, false);
            out.put("ok", true);
            out.put("id", wanted);
            return out;
        });
    }

    /**
     * Retire un point par id : relit le listing Hatari et cherche sa position par expression
     * juste avant de le retirer.
     *
     * @param m         machine, déjà détenue par l'appelant sous le verrou de session
     * @param id        id Java du point
     * @param kind      type attendu (« bp » ou « wp »)
     * @param tolerateVanished si vrai, un point absent du listing Hatari est simplement retiré
     *                         de la carte locale et signalé par la valeur de retour au lieu de
     *                         lever une exception
     * @return vrai si le point a bien été retiré côté Hatari, faux s'il avait déjà disparu
     */
    private boolean removeOne(Machine m, int id, String kind, boolean tolerateVanished) {
        Point p = points.get(id);
        if (p == null || !p.kind().equals(kind)) {
            throw new IllegalArgumentException(kind + " id inconnu: " + id);
        }
        OptionalInt position = MachineTools.breakpointPosition(m.debugCommand("b"), p.expression());
        if (position.isEmpty()) {
            points.remove(id);
            if (tolerateVanished) {
                return false;
            }
            throw new IllegalStateException("point " + id + " absent du listing Hatari (déjà retiré par :once ?)");
        }
        m.debugCommand("b " + position.getAsInt());
        points.remove(id);
        return true;
    }

    /** Position Hatari (1-based) d'une expression dans le listing « b », -1 si absente. */
    public static int positionOf(Machine m, String expression) {
        return MachineTools.breakpointPosition(m.debugCommand("b"), expression).orElse(-1);
    }

    private SyncToolSpecification setBreakpoint() {
        return tools.tool("set_breakpoint", "Pose un breakpoint sur PC = adresse hex. Retourne un id.",
                "{\"type\":\"object\",\"required\":[\"pc\"],\"properties\":{\"pc\":{\"type\":\"string\"}}}",
                args -> {
                    int pc = args.hex("pc");
                    Point p = add("bp", "pc = $" + Fmt.hex24(pc), null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", p.id());
                    out.put("pc", Fmt.hex24(pc));
                    out.put("expression", p.expression());
                    return out;
                });
    }

    private SyncToolSpecification clearBreakpoint() {
        return tools.tool("clear_breakpoint",
                "Retire un breakpoint par id, ou tous avec all=true ; avec all, les ids déjà disparus du "
                        + "listing Hatari (« :once » déclenché) sont reportés dans « vanished » sans erreur.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"all\":{\"type\":\"boolean\"}}}",
                args -> clear("bp", args));
    }

    private SyncToolSpecification setWatchpoint() {
        return tools.tool("set_watchpoint",
                "Arrête l'exécution quand la valeur à addr (len 1, 2 ou 4 octets) change. label optionnel.",
                "{\"type\":\"object\",\"required\":[\"addr\"],\"properties\":{\"addr\":{\"type\":\"string\"},"
                        + "\"len\":{\"type\":\"integer\",\"enum\":[1,2,4]},\"label\":{\"type\":\"string\"}}}",
                args -> {
                    int addr = args.hex("addr");
                    int len = args.intVal("len", 1);
                    String size = switch (len) {
                        case 1 -> "b";
                        case 2 -> "w";
                        case 4 -> "l";
                        default -> throw new IllegalArgumentException("len doit valoir 1, 2 ou 4");
                    };
                    String ref = "($" + Integer.toHexString(addr).toUpperCase() + ")." + size;
                    Point p = add("wp", ref + " ! " + ref, args.str("label", null));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", p.id());
                    out.put("addr", Fmt.hex24(addr));
                    out.put("len", len);
                    out.put("label", p.label());
                    out.put("expression", p.expression());
                    return out;
                });
    }

    private SyncToolSpecification clearWatchpoint() {
        return tools.tool("clear_watchpoint",
                "Retire un watchpoint par id, ou tous avec all=true ; avec all, les ids déjà disparus du "
                        + "listing Hatari sont reportés dans « vanished » sans erreur.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"all\":{\"type\":\"boolean\"}}}",
                args -> clear("wp", args));
    }

    private SyncToolSpecification listBreakpoints() {
        return tools.tool("list_breakpoints", "Liste les breakpoints et watchpoints posés par ce serveur, et le listing Hatari brut.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Point p : points.values()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", p.id());
                        row.put("kind", p.kind());
                        row.put("expression", p.expression());
                        row.put("label", p.label());
                        rows.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("points", rows);
                    out.put("hatari", m.debugCommand("b"));
                    return out;
                }));
    }

    private SyncToolSpecification loadSymbols() {
        return tools.tool("load_symbols", "Charge un fichier de symboles (ex. ~/.hatari/etos1024k.sym) dans le debugger.",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}",
                args -> {
                    String path = args.str("path").replaceFirst("^~", System.getProperty("user.home"));
                    String out = session.read(m -> m.debugCommand("symbols " + path));
                    return Map.of("output", out);
                });
    }

    private SyncToolSpecification debugCommand() {
        return tools.tool("debug_command",
                "Passerelle brute vers le debugger Hatari (voir doc/debugger.html) : 'r', 'm $4000', 'd', 'b', 'history'...",
                "{\"type\":\"object\",\"required\":[\"cmd\"],\"properties\":{\"cmd\":{\"type\":\"string\"}}}",
                args -> {
                    String cmd = args.str("cmd");
                    return Map.of("output", session.read(m -> m.debugCommand(cmd)));
                });
    }
}
