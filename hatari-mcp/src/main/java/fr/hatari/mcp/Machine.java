package fr.hatari.mcp;

/**
 * Contrat consommé par les outils MCP. Implémenté par {@code ffm.HatariCore}
 * (vraie machine) et par un fake dans les tests. Tous les appels sont
 * synchrones et supposés exécutés sur un seul thread.
 */
public interface Machine {

    /** Raison d'arrêt de {@link #run}. Valeurs alignées sur {@code HATARI_STOP_*} de debug_api.h. */
    enum StopReason {
        NONE, BREAKPOINT, STEPS, EXCEPTION, OTHER;

        public static StopReason of(int code) {
            return code >= 0 && code < values().length ? values()[code] : OTHER;
        }
    }

    /** Résultat de {@link #run}. */
    record RunResult(StopReason reason, int framesDone) {}

    /** Registres CPU : D0-D7, A0-A7, PC, SR, USP, ISP (20 valeurs non signées). */
    record Registers(long[] values) {
        public long d(int i) { return values[i]; }
        public long a(int i) { return values[8 + i]; }
        public long pc() { return values[16]; }
        public int sr() { return (int) values[17]; }
        public long usp() { return values[18]; }
        public long isp() { return values[19]; }
    }

    /** Image écran XRGB8888 copiée. */
    record Frame(int width, int height, int[] pixels) {}

    RunResult run(int maxFrames);
    void step(int instructions);
    void reset(boolean cold);
    long vblCount();
    long cycleCount();

    byte[] readMemory(int addr, int len);
    void writeMemory(int addr, byte[] data);
    Registers registers();
    void setRegister(String name, long value);

    /** Exécute une commande du debugger Hatari ; retourne sa sortie texte. */
    String debugCommand(String cmd);
    /** Désassemble ; retourne le texte. */
    String disassemble(int addr, int count);

    Frame frame();
    void key(int scancode, boolean press);
    void insertDisk(int drive, String path);
    void ejectDisk(int drive);

    /** Libère la machine (retro_deinit). */
    void close();
}
