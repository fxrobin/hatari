package fr.hatari.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine factice : mémoire 16 Mo lazy, registres, journal des appels. */
public class FakeMachine implements Machine {
    public final byte[] ram = new byte[0x100000];
    public final long[] regs = new long[20];
    public final List<String> calls = new ArrayList<>();
    public final Map<Integer, String> breakpoints = new LinkedHashMap<>();
    public long vbl;
    public StopReason nextStop = StopReason.NONE;
    public String debugOutput = "";
    private int nextBreakpointId = 1;

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
    @Override public String debugCommand(String cmd) {
        calls.add("dbg " + cmd);
        if (cmd.equals("b")) {
            // List breakpoints in Hatari format
            if (breakpoints.isEmpty()) {
                return "0 conditional CPU breakpoints:\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(breakpoints.size()).append(" conditional CPU breakpoints:\n");
            for (Map.Entry<Integer, String> e : breakpoints.entrySet()) {
                sb.append(String.format("%4d:\t%s\n", e.getKey(), e.getValue()));
            }
            return sb.toString();
        } else if (cmd.startsWith("b ") && cmd.substring(2).trim().matches("\\d+")) {
            // Remove breakpoint by position ("b <n>", digits only : distinct from an
            // expression, which may contain "=" (breakpoints) or "!" (watchpoints)).
            int pos = Integer.parseInt(cmd.substring(2).trim());
            breakpoints.remove(pos);
        } else if (cmd.startsWith("b ")) {
            // Add breakpoint: "b pc = $E00D98 :once" or similar
            String expr = cmd.substring(2);
            breakpoints.put(nextBreakpointId, expr);
            nextBreakpointId++;
        }
        return debugOutput;
    }
    @Override public String disassemble(int addr, int count) { return String.format("$%06x : jmp $1100\n", addr); }
    @Override public Frame frame() { return new Frame(320, 200, new int[320 * 200]); }
    public fr.hatari.mcp.video.AudioListener audioListener;
    @Override public void onAudio(fr.hatari.mcp.video.AudioListener l) { audioListener = l; }
    @Override public void key(int scancode, boolean press) { calls.add("key " + scancode + " " + press); }
    @Override public void joystick(int port, int mask) { calls.add("joystick " + port + " " + mask); }
    @Override public void insertDisk(int drive, String path) { calls.add("insert " + drive + " " + path); }
    @Override public void ejectDisk(int drive) { calls.add("eject " + drive); }
    @Override public void close() { calls.add("close"); }
}
