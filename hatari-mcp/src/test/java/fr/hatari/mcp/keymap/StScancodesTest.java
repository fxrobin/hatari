package fr.hatari.mcp.keymap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StScancodesTest {

    @Test
    void lowercaseLetterHasNoShift() {
        var k = StScancodes.ofChar('a').orElseThrow();
        assertEquals(0x1E, k.scancode());
        assertFalse(k.shift());
    }

    @Test
    void uppercaseLetterNeedsShift() {
        var k = StScancodes.ofChar('A').orElseThrow();
        assertEquals(0x1E, k.scancode());
        assertTrue(k.shift());
    }

    @Test
    void digitsAndPunctuation() {
        assertEquals(0x02, StScancodes.ofChar('1').orElseThrow().scancode());
        assertEquals(0x0B, StScancodes.ofChar('0').orElseThrow().scancode());
        var excl = StScancodes.ofChar('!').orElseThrow();
        assertEquals(0x02, excl.scancode());
        assertTrue(excl.shift());
        assertEquals(0x39, StScancodes.ofChar(' ').orElseThrow().scancode());
    }

    @Test
    void namedKeys() {
        assertEquals(0x1C, StScancodes.ofName("RETURN").orElseThrow().scancode());
        assertEquals(0x1C, StScancodes.ofName("return").orElseThrow().scancode());
        assertEquals(0x3B, StScancodes.ofName("F1").orElseThrow().scancode());
        assertEquals(0x44, StScancodes.ofName("F10").orElseThrow().scancode());
        assertEquals(0x1E, StScancodes.ofName("a").orElseThrow().scancode());
        assertTrue(StScancodes.ofName("BOGUS").isEmpty());
    }
}
