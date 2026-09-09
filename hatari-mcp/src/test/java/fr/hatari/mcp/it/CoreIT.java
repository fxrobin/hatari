package fr.hatari.mcp.it;

import fr.hatari.mcp.EmulatorSession;
import fr.hatari.mcp.Machine;
import fr.hatari.mcp.McpJson;
import fr.hatari.mcp.Options;
import fr.hatari.mcp.ToolCatalog;
import fr.hatari.mcp.ffm.HatariCore;
import fr.hatari.mcp.keymap.StScancodes;
import fr.hatari.mcp.tools.DebugTools;
import fr.hatari.mcp.tools.InputTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Tests sur la vraie .so : -Dhatari.core=... -Dhatari.tos=... (ignores sinon). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreIT {

    static HatariCore core;

    @BeforeAll
    void open() {
        String so = System.getProperty("hatari.core", "../build/src/libretro-hatari.so");
        String tos = System.getProperty("hatari.tos", System.getProperty("user.home") + "/.hatari/tos.img");
        assumeTrue(Files.exists(Path.of(so)), "pas de core : " + so);
        assumeTrue(Files.exists(Path.of(tos)), "pas de TOS : " + tos);
        core = HatariCore.open(new Options(Path.of(so), Path.of(tos), "st", 1));
    }

    @AfterAll
    void close() {
        if (core != null) core.close();
    }

    @Test
    @Order(1)
    void bootsEmuTosAndDrawsSomething() {
        Machine.RunResult r = core.run(300);
        assertEquals(Machine.StopReason.NONE, r.reason());
        assertEquals(300, r.framesDone());
        Machine.Frame f = core.frame();
        assertTrue(f.width() >= 320 && f.height() >= 200);
        long distinct = java.util.Arrays.stream(f.pixels()).distinct().count();
        assertTrue(distinct > 2, "ecran uniforme, EmuTOS n'a rien affiche");
    }

    @Test
    @Order(2)
    void memoryRoundTrip() {
        byte[] data = { 1, 2, 3, 4 };
        core.writeMemory(0x2000, data);
        assertArrayEquals(data, core.readMemory(0x2000, 4));
    }

    @Test
    @Order(3)
    void registersAndRomPc() {
        Machine.Registers regs = core.registers();
        assertTrue(regs.pc() >= 0xE00000 && regs.pc() < 0xF00000, "PC hors ROM : " + Long.toHexString(regs.pc()));
    }

    @Test
    @Order(4)
    void breakpointStopsRun() {
        long pc = core.registers().pc();
        core.run(1);
        long target = core.registers().pc();
        String out = core.debugCommand("b pc = $" + Long.toHexString(target) + " :once");
        assertTrue(out.contains("breakpoint"), out);
        Machine.RunResult r = core.run(500);
        assertEquals(Machine.StopReason.BREAKPOINT, r.reason(), "pc initial " + Long.toHexString(pc));
        assertEquals(target, core.registers().pc());
    }

    /**
     * Pas-a-pas depuis une sequence connue : 4 NOPs suivis d'un "bra.s" de retour,
     * comme tests/retro/test-debug-api.c. Apres 3 pas le PC doit etre sur le 4e NOP.
     * Les interruptions sont masquees (SR IPL = 7) ; ce test detourne le PC vers
     * la RAM, il s'execute donc en dernier.
     */
    @Test
    @Order(200)
    void stepStopsAfterInstructions() {
        core.writeMemory(0x2100, new byte[] {
            0x4E, 0x71, 0x4E, 0x71, 0x4E, 0x71, 0x4E, 0x71, 0x60, (byte) 0xF6
        });
        core.setRegister("SR", 0x2700);
        core.setRegister("PC", 0x2100);
        core.step(3);
        Machine.RunResult r = core.run(10);
        assertEquals(Machine.StopReason.STEPS, r.reason());
        assertEquals(0x2106, core.registers().pc());
    }

    /**
     * Reset alors que le PC a ete detourne vers la RAM : le coeur doit repartir du
     * vecteur de reset (ROM) au lieu de reprendre a l'ancien PC sur un materiel
     * fraichement reinitialise -- ce qui faisait mourir le processus hote.
     */
    @Test
    @Order(210)
    void resetFromRamPcReturnsToRom() {
        core.writeMemory(0x2100, new byte[] {
            0x4E, 0x71, 0x4E, 0x71, 0x4E, 0x71, 0x4E, 0x71, 0x60, (byte) 0xF6
        });
        core.setRegister("SR", 0x2700);
        core.setRegister("PC", 0x2100);
        core.run(1);
        core.reset(true);
        Machine.RunResult r = core.run(300);
        assertEquals(Machine.StopReason.NONE, r.reason());
        long pc = core.registers().pc();
        assertTrue(pc >= 0xE00000 && pc < 0xF00000, "PC hors ROM apres reset : " + Long.toHexString(pc));
    }

    @Test
    @Order(5)
    void disassembleReturnsText() {
        String txt = core.disassemble((int) core.registers().pc(), 4);
        assertFalse(txt.isBlank());
    }

    @Test
    @Order(6)
    void countersAdvanceWithRun() {
        long vbl = core.vblCount(), cycles = core.cycleCount();
        Machine.RunResult r = core.run(10);
        assertEquals(10, r.framesDone());
        assertEquals(vbl + 10, core.vblCount());
        assertTrue(core.cycleCount() > cycles);
    }

    @Test
    @Order(7)
    void breakpointListingMatchesParser() {
        core.debugCommand("b pc = $E00000");
        try {
            int pos = DebugTools.positionOf(core, "pc = $E00000");
            // Message paresseux : le listing ne doit pas etre relu (ni "b -1" envoye)
            // quand l'assertion passe.
            assertTrue(pos >= 1, () -> "listing non reconnu : " + core.debugCommand("b"));
        } finally {
            int pos = DebugTools.positionOf(core, "pc = $E00000");
            if (pos >= 1) core.debugCommand("b " + pos);
        }
    }

    /**
     * Bout en bout clavier : ESC pendant l'ecran d'accueil EmuTOS doit faire
     * avancer l'"early console" et donc changer l'affichage. Fait un reset
     * (donc apres les tests qui detournent PC/RAM) et remet la machine dans un
     * etat connu avant de presser la touche.
     *
     * <p>Temoin d'abord : sur la meme fenetre de trames, sans aucune touche,
     * l'ecran doit rester identique. Sans ce controle, une animation propre a
     * EmuTOS suffirait a faire passer l'assertion, meme sans le tap().
     */
    @Test
    @Order(300)
    void escapeKeyChangesScreen() {
        // 244 trames apres un reset a froid : fenetre ou l'ecran d'accueil EmuTOS est
        // stable sur les 62 trames suivantes (le temoin ci-dessous le verifie). Ailleurs
        // pendant le boot, l'affichage change tout seul et le test ne prouverait rien.
        final int settleFrames = 244;
        final int holdFrames = 2;
        final int gapFrames = 60;

        core.reset(true);
        core.run(settleFrames);
        int[] idleBefore = core.frame().pixels();
        core.run(holdFrames + gapFrames);
        int[] idleAfter = core.frame().pixels();
        assertArrayEquals(idleBefore, idleAfter,
                "l'ecran d'accueil change tout seul : le test ne prouverait rien sur la touche");

        core.reset(true);
        core.run(settleFrames);
        int[] before = core.frame().pixels();
        InputTools.tap(core, StScancodes.ofName("ESC").orElseThrow(), holdFrames, gapFrames);
        int[] after = core.frame().pixels();
        assertFalse(Arrays.equals(before, after), "ESC n'a pas modifie l'ecran d'accueil EmuTOS");
    }

    /**
     * clear_watchpoint par le chemin outil, contre le vrai Hatari : le watchpoint
     * pose doit apparaitre dans le listing "b", puis en disparaitre apres l'appel
     * a clear_watchpoint avec son id.
     */
    @Test
    @Order(310)
    void clearWatchpointRemovesItFromHatariListing() {
        ToolCatalog catalog = new ToolCatalog(new EmulatorSession(core), McpJson.defaultMapper());

        Map<String, Object> set = callTool(catalog, "set_watchpoint", Map.of("addr", "004000", "len", 2));
        String expression = (String) set.get("expression");
        assertTrue(DebugTools.positionOf(core, expression) >= 1,
                () -> "watchpoint absent du listing Hatari : " + core.debugCommand("b"));

        Map<String, Object> cleared = callTool(catalog, "clear_watchpoint", Map.of("id", set.get("id")));
        assertEquals(true, cleared.get("ok"));

        String listing = core.debugCommand("b");
        assertEquals(-1, DebugTools.positionOf(core, expression), "watchpoint toujours la : " + listing);
        assertFalse(listing.toLowerCase(java.util.Locale.ROOT).contains(expression.toLowerCase(java.util.Locale.ROOT)),
                "expression encore presente dans le listing : " + listing);
    }

    /** Appelle un outil du catalogue et retourne son contenu structure. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> callTool(ToolCatalog catalog, String name, Map<String, Object> args) {
        for (SyncToolSpecification spec : catalog.all()) {
            if (spec.tool().name().equals(name)) {
                CallToolResult r = spec.callHandler().apply(null, new CallToolRequest(name, args));
                assertNotEquals(Boolean.TRUE, r.isError(), () -> "erreur : " + r.content());
                return (Map<String, Object>) r.structuredContent();
            }
        }
        throw new AssertionError("outil absent : " + name);
    }

}
