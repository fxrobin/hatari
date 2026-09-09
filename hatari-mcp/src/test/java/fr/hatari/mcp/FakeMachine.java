package fr.hatari.mcp;

import java.util.ArrayList;
import java.util.List;

/** Machine factice : mémoire 16 Mo lazy, registres, journal des appels. */
public class FakeMachine implements Machine {
    public final byte[] ram = new byte[0x100000];
    public final long[] regs = new long[20];
    public final List<String> calls = new ArrayList<>();
    public long vbl;
    public StopReason nextStop = StopReason.NONE;
    public String debugOutput = "";

    @Override public RunResult run(int maxFrames) {
        calls.add("run " + maxFrames);
        StopReason r = nextStop;
        nextStop = StopReason.NONE;
        int done = r == StopReason.NONE ? maxFrames : 0;
        vbl += done;
        return new RunResult(r, done);
    }
    @Override public void step(int n) { calls.add("step " + n); nextStop = StopReason.STEPS; }
    @Override public void reset(boolean cold) { calls.add("reset " + cold); }
    @Override public long vblCount() { return vbl; }
    @Override public long cycleCount() { return vbl * 160256; }
    @Override public byte[] readMemory(int addr, int len) {
        byte[] out = new byte[len];
        System.arraycopy(ram, addr, out, 0, len);
        return out;
    }
    @Override public void writeMemory(int addr, byte[] data) { System.arraycopy(data, 0, ram, addr, data.length); }
    @Override public Registers registers() { return new Registers(regs.clone()); }
    @Override public void setRegister(String name, long value) { calls.add("reg " + name + "=" + value); }
    @Override public String debugCommand(String cmd) { calls.add("dbg " + cmd); return debugOutput; }
    @Override public String disassemble(int addr, int count) { return String.format("$%06x : jmp $1100\n", addr); }
    @Override public Frame frame() { return new Frame(320, 200, new int[320 * 200]); }
    @Override public void key(int scancode, boolean press) { calls.add("key " + scancode + " " + press); }
    @Override public void insertDisk(int drive, String path) { calls.add("insert " + drive + " " + path); }
    @Override public void ejectDisk(int drive) { calls.add("eject " + drive); }
    @Override public void close() { calls.add("close"); }
}
