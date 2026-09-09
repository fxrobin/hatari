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
    void memoryRoundTrip() {
        byte[] data = { 1, 2, 3, 4 };
        core.writeMemory(0x2000, data);
        assertArrayEquals(data, core.readMemory(0x2000, 4));
    }

    @Test
    void registersAndRomPc() {
        Machine.Registers regs = core.registers();
        assertTrue(regs.pc() >= 0xE00000 && regs.pc() < 0xF00000, "PC hors ROM : " + Long.toHexString(regs.pc()));
    }

    @Test
    void breakpointStopsRun() {
        long pc = core.registers().pc();
        core.run(1);
        long target = core.registers().pc();
        String out = core.debugCommand("b pc = $" + Long.toHexString(target) + " :once");
        assertTrue(out.contains("breakpoint"), out);
        Machine.RunResult r = core.run(500);
        assertEquals(Machine.StopReason.BREAKPOINT, r.reason(), "pc initial " + Long.toHexString(pc));
        // Hatari rend la main a la fin de l'instruction ou le point d'arret a matche :
        // le PC est donc soit sur la cible, soit sur l'instruction suivante.
        long after = core.registers().pc();
        assertTrue(after == target || after == nextPc(target),
                "pc apres arret : " + Long.toHexString(after) + ", cible " + Long.toHexString(target));
    }

    @Test
    void stepStopsAfterInstructions() {
        core.step(5);
        Machine.RunResult r = core.run(10);
        assertEquals(Machine.StopReason.STEPS, r.reason());
    }

    @Test
    void disassembleReturnsText() {
        String txt = core.disassemble((int) core.registers().pc(), 4);
        assertFalse(txt.isBlank());
    }

    @Test
    @Order(100)
    void countersAdvanceWithRun() {
        long vbl = core.vblCount(), cycles = core.cycleCount();
        Machine.RunResult r = core.run(10);
        assertEquals(10, r.framesDone());
        assertEquals(vbl + 10, core.vblCount());
        assertTrue(core.cycleCount() > cycles);
    }

    /** Adresse de l'instruction suivant {@code addr}, lue sur la 2e ligne du desassemblage. */
    private static long nextPc(long addr) {
        String[] lines = core.disassemble((int) addr, 2).split("\n");
        assertTrue(lines.length >= 2, "desassemblage trop court");
        return Long.parseLong(lines[1].split("\\s+")[0], 16);
    }
}
