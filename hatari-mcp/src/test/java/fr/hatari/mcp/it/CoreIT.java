package fr.hatari.mcp.it;

import fr.hatari.mcp.Machine;
import fr.hatari.mcp.Options;
import fr.hatari.mcp.ffm.HatariCore;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

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
        int pos = fr.hatari.mcp.tools.DebugTools.positionOf(core, "pc = $E00000");
        core.debugCommand("b " + pos);
        assertTrue(pos >= 1, "listing non reconnu : " + core.debugCommand("b"));
    }

}
