package fr.hatari.mcp;

import fr.hatari.mcp.video.VideoRecorder;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Une session = une machine Hatari. Sérialise tous les accès au cœur natif
 * (un seul thread doit toucher le cœur libretro).
 */
public final class EmulatorSession implements AutoCloseable {

    private final Object lock = new Object();
    private final CapturingMachine machine;

    public EmulatorSession(Machine machine) {
        this(machine, CapturingMachine.CPU_HZ_ST);
    }

    /** @param cpuHz fréquence du CPU émulé (base de temps de la capture vidéo) */
    public EmulatorSession(Machine machine, long cpuHz) {
        this.machine = new CapturingMachine(machine, cpuHz);
    }

    /** Capture vidéo de la session (alimentée par tout outil qui fait avancer la machine). */
    public VideoRecorder recorder() {
        return machine.recorder();
    }

    public <T> T read(Function<Machine, T> fn) {
        synchronized (lock) {
            return fn.apply(machine);
        }
    }

    public void mutate(Consumer<Machine> fn) {
        synchronized (lock) {
            fn.accept(machine);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            machine.close();
        }
    }
}
