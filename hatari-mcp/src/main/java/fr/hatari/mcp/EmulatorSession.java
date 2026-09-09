package fr.hatari.mcp;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Une session = une machine Hatari. Sérialise tous les accès au cœur natif
 * (un seul thread doit toucher le cœur libretro).
 */
public final class EmulatorSession implements AutoCloseable {

    private final Object lock = new Object();
    private final Machine machine;

    public EmulatorSession(Machine machine) {
        this.machine = machine;
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
