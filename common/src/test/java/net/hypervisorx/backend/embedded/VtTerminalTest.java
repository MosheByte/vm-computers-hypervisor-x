package net.hypervisorx.backend.embedded;

import net.hypervisorx.vm.input.KeyMaps;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VtTerminalTest {
    private final ByteArrayOutputStream replies = new ByteArrayOutputStream();
    private final List<String> osc = new ArrayList<>();

    private VtTerminal term(int cols, int rows) {
        return new VtTerminal(cols, rows, new VtTerminal.Host() {
            @Override
            public void reply(byte[] data) {
                replies.writeBytes(data);
            }

            @Override
            public void osc(String payload) {
                osc.add(payload);
            }

            @Override
            public void bell() {
            }
        });
    }

    @Test
    void textCursorAndErase() {
        VtTerminal t = term(20, 5);
        t.writeString("Hello\r\nWorld\033[1;3HX\033[2;1H\033[K");
        String[] lines = t.screenText().split("\n", -1);
        assertEquals("HeXlo", lines[0]);
        assertEquals("", lines[1]);
    }

    @Test
    void scrollingAndUtf8() {
        VtTerminal t = term(10, 3);
        t.writeString("a\r\nb\r\nc\r\nd é─");
        String[] lines = t.screenText().split("\n", -1);
        assertEquals("b", lines[0]);
        assertEquals("d é─", lines[2]);
    }

    @Test
    void deviceStatusAndGpioOsc() {
        VtTerminal t = term(10, 3);
        t.writeString("ab\033[6n\033]1337;GPIO;SET;north;15\007");
        assertEquals("\033[1;3R", replies.toString());
        assertEquals(List.of("1337;GPIO;SET;north;15"), osc);
        assertTrue(t.screenText().startsWith("ab"));
    }

    @Test
    void keyMaps() {
        assertEquals(0x1E, KeyMaps.scancode('A'));
        assertArrayEquals(new int[]{0xE0, 0x48}, KeyMaps.scancodeBytes(KeyMaps.KEY_UP, true));
        assertArrayEquals(new int[]{0xE0, 0xC8}, KeyMaps.scancodeBytes(KeyMaps.KEY_UP, false));
        assertEquals(0xC8, KeyMaps.qemuKeycode(KeyMaps.KEY_UP));
        assertEquals('a', KeyMaps.keysym('A'));
        assertEquals(0xff0d, KeyMaps.keysym(KeyMaps.KEY_ENTER));
        assertArrayEquals(new byte[]{3}, KeyMaps.terminalSequence('C', true, false, false));
        assertArrayEquals("\033OA".getBytes(), KeyMaps.terminalSequence(KeyMaps.KEY_UP, false, false, true));
    }
}
