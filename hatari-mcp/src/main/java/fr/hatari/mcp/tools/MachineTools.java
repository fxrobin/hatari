package fr.hatari.mcp.tools;

import fr.hatari.mcp.Args;
import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Fmt;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Tools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outils de contrôle de la machine : ping, état, reset, exécution. */
public final class MachineTools {

    /** Garde-fou par défaut pour run_until_pc / run_to_breakpoint (≈ 20 s machine à 50 Hz). */
    static final int DEFAULT_MAX_FRAMES = 1000;

    private final EmulatorSession session;
    private final Tools tools;

    public MachineTools(EmulatorSession session, Tools tools) {
        this.session = session;
        this.tools = tools;
    }

    public List<SyncToolSpecification> specs() {
        return List.of(ping(), machineState(), reset(), runFrames(), runUntilPc(), runToBreakpoint(), step());
    }

    /** Compte rendu commun à toutes les exécutions. */
    public static Map<String, Object> runReport(Machine m, Machine.RunResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.reason().name());
        out.put("frames_done", r.framesDone());
        out.put("pc", Fmt.hex24((int) m.registers().pc()));
        out.put("vbl", m.vblCount());
        return out;
    }

    private SyncToolSpecification ping() {
        return tools.tool("ping", "Vérifie que le serveur et la machine émulée répondent.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> Map.of("ok", true, "vbl", m.vblCount())));
    }

    private SyncToolSpecification machineState() {
        return tools.tool("machine_state",
                "État courant : compteur VBL, cycles CPU, registres et drapeaux.",
                "{\"type\":\"object\",\"properties\":{}}",
                args -> session.read(m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("vbl", m.vblCount());
                    out.put("cycles", m.cycleCount());
                    out.put("registers", Fmt.registers(m.registers()));
                    return out;
                }));
    }

    private SyncToolSpecification reset() {
        return tools.tool("reset", "Reset de la machine (cold par défaut, warm si cold=false).",
                "{\"type\":\"object\",\"properties\":{\"cold\":{\"type\":\"boolean\"}}}",
                args -> {
                    boolean cold = args.bool("cold", true);
                    return session.read(m -> {
                        m.reset(cold);
                        Machine.RunResult r = m.run(1);
                        return runReport(m, r);
                    });
                });
    }

    private SyncToolSpecification runFrames() {
        return tools.tool("run_frames",
                "Exécute n trames (VBL, 50 Hz). S'arrête plus tôt sur breakpoint/watchpoint/step.",
                "{\"type\":\"object\",\"required\":[\"n\"],\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100000}}}",
                args -> {
                    int n = args.intVal("n");
                    return session.read(m -> runReport(m, m.run(n)));
                });
    }

    private SyncToolSpecification runUntilPc() {
        return tools.tool("run_until_pc",
                "Exécute jusqu'à ce que PC atteigne l'adresse hex donnée (breakpoint temporaire), "
                        + "au plus max_frames trames (défaut " + DEFAULT_MAX_FRAMES + ").",
                "{\"type\":\"object\",\"required\":[\"pc\"],\"properties\":{"
                        + "\"pc\":{\"type\":\"string\"},\"max_frames\":{\"type\":\"integer\",\"minimum\":1}}}",
                args -> {
                    int pc = args.hex("pc");
                    int max = args.intVal("max_frames", DEFAULT_MAX_FRAMES);
                    return session.read(m -> {
                        m.debugCommand("b pc = $" + Fmt.hex24(pc) + " :once");
                        Machine.RunResult r = m.run(max);
                        Map<String, Object> out = runReport(m, r);
                        out.put("reached", r.reason() == Machine.StopReason.BREAKPOINT);
                        return out;
                    });
                });
    }

    private SyncToolSpecification runToBreakpoint() {
        return tools.tool("run_to_breakpoint",
                "Exécute jusqu'au prochain breakpoint/watchpoint, au plus max_frames trames (défaut "
                        + DEFAULT_MAX_FRAMES + ").",
                "{\"type\":\"object\",\"properties\":{\"max_frames\":{\"type\":\"integer\",\"minimum\":1}}}",
                args -> {
                    int max = args.intVal("max_frames", DEFAULT_MAX_FRAMES);
                    return session.read(m -> runReport(m, m.run(max)));
                });
    }

    private SyncToolSpecification step() {
        return tools.tool("step", "Exécute n instructions CPU (défaut 1) puis s'arrête.",
                "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000000}}}",
                args -> {
                    int n = args.intVal("n", 1);
                    return session.read(m -> {
                        m.step(n);
                        // n instructions tiennent dans bien moins d'une seconde machine ;
                        // 50 trames = garde-fou si le CPU est arrêté (STOP) ou en boucle d'attente.
                        Machine.RunResult r = m.run(n <= 10_000 ? 1 : 50);
                        return runReport(m, r);
                    });
                });
    }
}
