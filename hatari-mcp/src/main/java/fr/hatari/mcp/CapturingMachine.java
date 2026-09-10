package fr.hatari.mcp;

import fr.hatari.mcp.video.VideoRecorder;

/**
 * Décore une {@link Machine} pour alimenter le {@link VideoRecorder} : quand une capture
 * est active, {@link #run} avance par pas au plus égaux à l'intervalle d'échantillonnage
 * et signale chaque pas au recorder. Sans capture, délègue tel quel.
 */
public final class CapturingMachine implements Machine {

    private final Machine inner;
    private final VideoRecorder recorder = new VideoRecorder();

    public CapturingMachine(Machine inner) { this.inner = inner; }

    public VideoRecorder recorder() { return recorder; }

    @Override
    public RunResult run(int maxFrames) {
        if (!recorder.active()) return inner.run(maxFrames);
        int done = 0;
        while (done < maxFrames) {
            int n = Math.min(maxFrames - done, recorder.chunk());
            RunResult r = inner.run(n);
            done += r.framesDone();
            recorder.advanced(inner, r.framesDone());
            if (r.reason() != StopReason.NONE) return new RunResult(r.reason(), done);
        }
        return new RunResult(StopReason.NONE, done);
    }

    @Override public void step(int instructions) { inner.step(instructions); }
    @Override public void reset(boolean cold) { inner.reset(cold); }
    @Override public long vblCount() { return inner.vblCount(); }
    @Override public long cycleCount() { return inner.cycleCount(); }
    @Override public byte[] readMemory(int addr, int len) { return inner.readMemory(addr, len); }
    @Override public void writeMemory(int addr, byte[] data) { inner.writeMemory(addr, data); }
    @Override public Registers registers() { return inner.registers(); }
    @Override public void setRegister(String name, long value) { inner.setRegister(name, value); }
    @Override public String debugCommand(String cmd) { return inner.debugCommand(cmd); }
    @Override public String disassemble(int addr, int count) { return inner.disassemble(addr, count); }
    @Override public Frame frame() { return inner.frame(); }
    @Override public void key(int scancode, boolean press) { inner.key(scancode, press); }
    @Override public void joystick(int port, int mask) { inner.joystick(port, mask); }
    @Override public void insertDisk(int drive, String path) { inner.insertDisk(drive, path); }
    @Override public void ejectDisk(int drive) { inner.ejectDisk(drive); }
    @Override public void close() { inner.close(); }
}
