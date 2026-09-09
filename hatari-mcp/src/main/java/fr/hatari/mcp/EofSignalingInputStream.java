package fr.hatari.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flux d'entrée qui prévient, une seule fois, quand la source atteint sa fin.
 *
 * <p>Raison d'être : un client MCP stdio n'arrête pas son serveur en le tuant,
 * il <b>ferme stdin</b>. Le SDK 2.0.0 ne publie aucun rappel de fermeture
 * ({@code StdioServerTransportProvider} n'expose ni {@code onClose} ni attente
 * de fin), mais c'est nous qui lui fournissons le flux d'entrée : l'interposer
 * ici est le seul point d'observation de cette fin de flux.
 *
 * <p>Sans ce signal, {@link StdioMcpMain} dormait indéfiniment et laissait une
 * JVM d'émulateur vivante par session — 118 orphelines relevées sur un poste,
 * soit une vingtaine de gigaoctets de mémoire retenue.
 *
 * <p>Le rappel est exécuté sur le thread de lecture du transport : il doit
 * rendre la main tout de suite (poser un drapeau, libérer un verrou), jamais
 * arrêter le serveur lui-même.
 */
final class EofSignalingInputStream extends FilterInputStream {

    private final Runnable onEof;
    private final AtomicBoolean signaled = new AtomicBoolean();

    EofSignalingInputStream(InputStream in, Runnable onEof) {
        super(Objects.requireNonNull(in, "in"));
        this.onEof = Objects.requireNonNull(onEof, "onEof");
    }

    @Override
    public int read() throws IOException {
        return signalIfEof(super.read());
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return signalIfEof(super.read(b, off, len));
    }

    private int signalIfEof(int result) {
        // Une fin de flux se relit autant de fois qu'on l'interroge ; le rappel,
        // lui, ne doit partir qu'une fois (il déclenche l'arrêt du serveur).
        if (result < 0 && signaled.compareAndSet(false, true)) {
            onEof.run();
        }
        return result;
    }
}
