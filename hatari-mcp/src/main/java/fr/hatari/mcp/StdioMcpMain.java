package fr.hatari.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée du serveur MCP stdio de hatari-mcp.
 *
 * <p>Le transport stdio réserve <b>stdout au protocole JSON-RPC</b> : toute
 * impression parasite le casse. On isole donc le vrai descripteur stdout
 * pour le transport puis on redirige {@code System.out} vers {@code System.err}
 * — tout {@code System.out.println} égaré part sur stderr, jamais dans le flux.
 *
 * <p>Le serveur s'arrête sur <b>fin de flux de stdin</b> : c'est ainsi qu'un client
 * MCP stdio demande l'arrêt, et non par un signal. Voir
 * {@link EofSignalingInputStream}, qui porte le pourquoi de ce détour.
 */
public final class StdioMcpMain {

    /** Délai laissé à l'arrêt gracieux avant coupure sèche. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /**
     * Sursis accordé aux réponses déjà en vol après la fin de stdin.
     *
     * <p>Mesuré : une requête suivie d'une fermeture immédiate de stdin arrive dans
     * la même lecture que la fin de flux ; sans ce sursis, la réponse était écrite
     * dans le vide et le client la perdait. Un client qui referme stdin n'attend en
     * principe plus rien, mais le coût de la prudence est nul.
     */
    private static final Duration DRAIN = Duration.ofMillis(250);

    private StdioMcpMain() {}

    public static void main(String[] args) throws Exception {
        // Capture le vrai stdout pour le transport, puis dévie System.out vers stderr.
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        System.setOut(System.err);

        // Fin de stdin = demande d'arrêt du client. Le rappel se contente d'ouvrir
        // le verrou : l'arrêt lui-même appartient au thread principal, le rappel
        // s'exécutant sur le thread de lecture du transport.
        CountDownLatch stopped = new CountDownLatch(1);
        InputStream in = new EofSignalingInputStream(System.in, stopped::countDown);

        McpJsonMapper mapper = McpJson.defaultMapper();
        var transport = new StdioServerTransportProvider(mapper, in, protocolOut);

        Options options = Options.parse(args);
        EmulatorSession session = new EmulatorSession(fr.hatari.mcp.ffm.HatariCore.open(options));
        Runtime.getRuntime().addShutdownHook(new Thread(session::close));

        ToolCatalog catalog = new ToolCatalog(session, mapper);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("hatari-emulator", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .instructions("Émulateur Atari ST/STE/TT/Falcon (Hatari, CPU 68000). Inspecte et pilote "
                        + "une machine émulée : registres, mémoire, désassemblage, breakpoints, "
                        + "exécution par trames ou pas-à-pas, clavier, disquettes, screenshot.")
                .tools(catalog.all())
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));

        // Le serveur écoute sur son propre thread ; on garde la JVM vivante jusqu'à
        // ce que le client ferme stdin.
        stopped.await();
        Thread.sleep(DRAIN.toMillis());

        // Filet : l'arrêt gracieux du hook est une E/S réseau-like, rien ne garantit
        // qu'il rende la main. Une JVM bloquée dans son hook est exactement
        // l'orphelin qu'on supprime — au bout du délai, on coupe sans discuter.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_GRACE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(0);
        }, "hatari-mcp-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // Sortie explicite (elle déclenche le hook, donc l'arrêt gracieux) : ni les
        // threads du transport ni ceux de l'émulateur ne sont des démons, la JVM
        // survivrait au simple retour de main().
        System.exit(0);
    }
}
