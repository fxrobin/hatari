package fr.hatari.mcp.ffm;

import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Options;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

/** Bindings FFM vers libretro-hatari.so + debug_api. Seule classe qui manipule MemorySegment. */
public final class HatariCore implements Machine {

    private static final int TEXT_BUF = 1 << 20;
    private static final int REGS_COUNT = 20;
    private static final FunctionDescriptor SET_CB = FunctionDescriptor.ofVoid(ADDRESS);

    private final Arena arena = Arena.ofShared();
    private final MethodHandle run, step, reset, counters, memRead, memWrite, regsGet, regSet,
            dbgCommand, disasm, framebuffer, key, diskInsert, diskEject, deinit;
    private final MemorySegment textBuf;
    private boolean closed;

    public static HatariCore open(Options options) {
        try {
            return new HatariCore(options);
        } catch (Throwable t) {
            throw new IllegalStateException("chargement du core Hatari impossible : " + t.getMessage(), t);
        }
    }

    private HatariCore(Options options) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(options.core().toAbsolutePath().toString(), arena);
        RetroCallbacks cb = new RetroCallbacks(linker, arena);

        down(linker, lib, "retro_set_environment", SET_CB).invoke(cb.env);
        down(linker, lib, "retro_set_video_refresh", SET_CB).invoke(cb.video);
        down(linker, lib, "retro_set_audio_sample", SET_CB).invoke(cb.audio);
        down(linker, lib, "retro_set_audio_sample_batch", SET_CB).invoke(cb.audioBatch);
        down(linker, lib, "retro_set_input_poll", SET_CB).invoke(cb.inputPoll);
        down(linker, lib, "retro_set_input_state", SET_CB).invoke(cb.inputState);

        String[] args = options.hatariArgs();
        MemorySegment argv = arena.allocate(ADDRESS, args.length);
        for (int i = 0; i < args.length; i++) argv.setAtIndex(ADDRESS, i, arena.allocateFrom(args[i]));
        down(linker, lib, "hatari_init_args", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)).invoke(args.length, argv);
        down(linker, lib, "retro_load_game", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS)).invoke(MemorySegment.NULL);

        run = down(linker, lib, "hatari_run", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        step = down(linker, lib, "hatari_step", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        reset = down(linker, lib, "hatari_reset", FunctionDescriptor.ofVoid(JAVA_BOOLEAN));
        counters = down(linker, lib, "hatari_counters", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        memRead = down(linker, lib, "hatari_mem_read", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        memWrite = down(linker, lib, "hatari_mem_write", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
        regsGet = down(linker, lib, "hatari_regs_get", FunctionDescriptor.ofVoid(ADDRESS));
        regSet = down(linker, lib, "hatari_reg_set", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        dbgCommand = down(linker, lib, "hatari_dbg_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        disasm = down(linker, lib, "hatari_disasm", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        framebuffer = down(linker, lib, "hatari_framebuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        key = down(linker, lib, "hatari_key", FunctionDescriptor.ofVoid(JAVA_BYTE, JAVA_BOOLEAN));
        diskInsert = down(linker, lib, "hatari_disk_insert", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        diskEject = down(linker, lib, "hatari_disk_eject", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        deinit = down(linker, lib, "retro_deinit", FunctionDescriptor.ofVoid());
        textBuf = arena.allocate(TEXT_BUF);
    }

    private static MethodHandle down(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.findOrThrow(name), fd);
    }

    private static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    @Override
    public RunResult run(int maxFrames) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment reason = a.allocate(JAVA_INT), done = a.allocate(JAVA_INT);
            run.invoke(maxFrames, reason, done);
            return new RunResult(StopReason.of(reason.get(JAVA_INT, 0)), done.get(JAVA_INT, 0));
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void step(int instructions) {
        try {
            if ((int) step.invoke(instructions) != 0) throw new IllegalArgumentException("steps doit etre > 0");
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void reset(boolean cold) {
        try { reset.invoke(cold); } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public long vblCount() { return counters()[0]; }

    @Override
    public long cycleCount() { return counters()[1]; }

    private long[] counters() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vbl = a.allocate(JAVA_INT), cyc = a.allocate(JAVA_LONG);
            counters.invoke(vbl, cyc);
            return new long[] { Integer.toUnsignedLong(vbl.get(JAVA_INT, 0)), cyc.get(JAVA_LONG, 0) };
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public byte[] readMemory(int addr, int len) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(len);
            if ((int) memRead.invoke(addr, buf, (long) len) != 0) throw new IllegalArgumentException("adresse hors plage");
            return buf.toArray(JAVA_BYTE);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void writeMemory(int addr, byte[] data) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocateFrom(JAVA_BYTE, data);
            if ((int) memWrite.invoke(addr, buf, (long) data.length) != 0) throw new IllegalArgumentException("adresse hors plage");
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public Registers registers() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_INT, REGS_COUNT);
            regsGet.invoke(out);
            long[] v = new long[REGS_COUNT];
            for (int i = 0; i < REGS_COUNT; i++) v[i] = Integer.toUnsignedLong(out.getAtIndex(JAVA_INT, i));
            return new Registers(v);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void setRegister(String name, long value) {
        try (Arena a = Arena.ofConfined()) {
            if ((int) regSet.invoke(a.allocateFrom(name), (int) value) != 0)
                throw new IllegalArgumentException("registre inconnu : " + name);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public String debugCommand(String cmd) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) dbgCommand.invoke(a.allocateFrom(cmd), textBuf, (long) TEXT_BUF);
            if (rc < 0) throw new IllegalStateException("capture de la sortie impossible");
            return textBuf.getString(0, StandardCharsets.ISO_8859_1);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public String disassemble(int addr, int count) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment next = a.allocate(JAVA_INT);
            disasm.invoke(addr, count, textBuf, (long) TEXT_BUF, next);
            return textBuf.getString(0, StandardCharsets.ISO_8859_1);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public Frame frame() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment px = a.allocate(ADDRESS), w = a.allocate(JAVA_INT), h = a.allocate(JAVA_INT);
            if ((int) framebuffer.invoke(px, w, h) != 0) throw new IllegalStateException("framebuffer non alloue");
            int width = w.get(JAVA_INT, 0), height = h.get(JAVA_INT, 0);
            MemorySegment pixels = px.get(ADDRESS, 0).reinterpret((long) width * height * 4);
            return new Frame(width, height, pixels.toArray(JAVA_INT));
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void key(int scancode, boolean press) {
        try { key.invoke((byte) scancode, press); } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void insertDisk(int drive, String path) {
        try (Arena a = Arena.ofConfined()) {
            int rc = (int) diskInsert.invoke(drive, a.allocateFrom(path));
            switch (rc) {
                case 0 -> {}
                case -1 -> throw new IllegalArgumentException("lecteur invalide : " + drive);
                case -2 -> throw new IllegalArgumentException("image introuvable : " + path);
                default -> throw new IllegalStateException("insertion refusee par Hatari : " + path);
            }
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void ejectDisk(int drive) {
        try {
            if ((int) diskEject.invoke(drive) != 0) throw new IllegalArgumentException("lecteur invalide : " + drive);
        } catch (Throwable t) { throw wrap(t); }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { deinit.invoke(); } catch (Throwable t) { throw wrap(t); }
        arena.close();
    }
}
