package net.hypervisorx.vm.input;

import java.nio.charset.StandardCharsets;

/**
 * GLFW key codes (layout-independent, what Minecraft gives us) to whatever each backend wants:
 * X11 keysyms for RFB/VNC, PC/AT scan code set 1 for VirtualBox and QEMU extended key events,
 * VT220 byte sequences for the embedded terminal.
 */
public final class KeyMaps {
    // GLFW key codes, copied from org.lwjgl.glfw.GLFW so this class doesn't drag in the client.
    public static final int KEY_SPACE = 32;
    public static final int KEY_A = 65;
    public static final int KEY_Z = 90;
    public static final int KEY_ESCAPE = 256;
    public static final int KEY_ENTER = 257;
    public static final int KEY_TAB = 258;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_INSERT = 260;
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_DOWN = 264;
    public static final int KEY_UP = 265;
    public static final int KEY_PAGE_UP = 266;
    public static final int KEY_PAGE_DOWN = 267;
    public static final int KEY_HOME = 268;
    public static final int KEY_END = 269;
    public static final int KEY_CAPS_LOCK = 280;
    public static final int KEY_SCROLL_LOCK = 281;
    public static final int KEY_NUM_LOCK = 282;
    public static final int KEY_PRINT_SCREEN = 283;
    public static final int KEY_PAUSE = 284;
    public static final int KEY_F1 = 290;
    public static final int KEY_F12 = 301;
    public static final int KEY_KP_0 = 320;
    public static final int KEY_KP_9 = 329;
    public static final int KEY_KP_DECIMAL = 330;
    public static final int KEY_KP_DIVIDE = 331;
    public static final int KEY_KP_MULTIPLY = 332;
    public static final int KEY_KP_SUBTRACT = 333;
    public static final int KEY_KP_ADD = 334;
    public static final int KEY_KP_ENTER = 335;
    public static final int KEY_KP_EQUAL = 336;
    public static final int KEY_LEFT_SHIFT = 340;
    public static final int KEY_LEFT_CONTROL = 341;
    public static final int KEY_LEFT_ALT = 342;
    public static final int KEY_LEFT_SUPER = 343;
    public static final int KEY_RIGHT_SHIFT = 344;
    public static final int KEY_RIGHT_CONTROL = 345;
    public static final int KEY_RIGHT_ALT = 346;
    public static final int KEY_RIGHT_SUPER = 347;
    public static final int KEY_MENU = 348;
    public static final int KEY_WORLD_1 = 161;

    private static final int[] KEYSYM = new int[400];
    /** Scan code set 1. Anything >= 0xE000 gets the E0 prefix. */
    private static final int[] SCANCODE = new int[400];

    static {
        // printable keys just use their unshifted ASCII keysym
        String printable = " '+,-./0123456789;=[\\]`";
        for (char c : printable.toCharArray()) KEYSYM[c] = c;
        for (int k = KEY_A; k <= KEY_Z; k++) KEYSYM[k] = Character.toLowerCase((char) k);
        KEYSYM[KEY_WORLD_1] = '<';
        KEYSYM[KEY_ESCAPE] = 0xff1b;
        KEYSYM[KEY_ENTER] = 0xff0d;
        KEYSYM[KEY_TAB] = 0xff09;
        KEYSYM[KEY_BACKSPACE] = 0xff08;
        KEYSYM[KEY_INSERT] = 0xff63;
        KEYSYM[KEY_DELETE] = 0xffff;
        KEYSYM[KEY_RIGHT] = 0xff53;
        KEYSYM[KEY_LEFT] = 0xff51;
        KEYSYM[KEY_DOWN] = 0xff54;
        KEYSYM[KEY_UP] = 0xff52;
        KEYSYM[KEY_PAGE_UP] = 0xff55;
        KEYSYM[KEY_PAGE_DOWN] = 0xff56;
        KEYSYM[KEY_HOME] = 0xff50;
        KEYSYM[KEY_END] = 0xff57;
        KEYSYM[KEY_CAPS_LOCK] = 0xffe5;
        KEYSYM[KEY_SCROLL_LOCK] = 0xff14;
        KEYSYM[KEY_NUM_LOCK] = 0xff7f;
        KEYSYM[KEY_PRINT_SCREEN] = 0xff61;
        KEYSYM[KEY_PAUSE] = 0xff13;
        for (int i = 0; i < 12; i++) KEYSYM[KEY_F1 + i] = 0xffbe + i;
        for (int i = 0; i <= 9; i++) KEYSYM[KEY_KP_0 + i] = 0xffb0 + i;
        KEYSYM[KEY_KP_DECIMAL] = 0xffae;
        KEYSYM[KEY_KP_DIVIDE] = 0xffaf;
        KEYSYM[KEY_KP_MULTIPLY] = 0xffaa;
        KEYSYM[KEY_KP_SUBTRACT] = 0xffad;
        KEYSYM[KEY_KP_ADD] = 0xffab;
        KEYSYM[KEY_KP_ENTER] = 0xff8d;
        KEYSYM[KEY_KP_EQUAL] = 0xffbd;
        KEYSYM[KEY_LEFT_SHIFT] = 0xffe1;
        KEYSYM[KEY_RIGHT_SHIFT] = 0xffe2;
        KEYSYM[KEY_LEFT_CONTROL] = 0xffe3;
        KEYSYM[KEY_RIGHT_CONTROL] = 0xffe4;
        KEYSYM[KEY_LEFT_ALT] = 0xffe9;
        KEYSYM[KEY_RIGHT_ALT] = 0xffea;
        KEYSYM[KEY_LEFT_SUPER] = 0xffeb;
        KEYSYM[KEY_RIGHT_SUPER] = 0xffec;
        KEYSYM[KEY_MENU] = 0xff67;

        int[][] sc = {
                {KEY_ESCAPE, 0x01}, {'1', 0x02}, {'2', 0x03}, {'3', 0x04}, {'4', 0x05}, {'5', 0x06},
                {'6', 0x07}, {'7', 0x08}, {'8', 0x09}, {'9', 0x0A}, {'0', 0x0B}, {'-', 0x0C}, {'=', 0x0D},
                {KEY_BACKSPACE, 0x0E}, {KEY_TAB, 0x0F}, {'Q', 0x10}, {'W', 0x11}, {'E', 0x12}, {'R', 0x13},
                {'T', 0x14}, {'Y', 0x15}, {'U', 0x16}, {'I', 0x17}, {'O', 0x18}, {'P', 0x19}, {'[', 0x1A},
                {']', 0x1B}, {KEY_ENTER, 0x1C}, {KEY_LEFT_CONTROL, 0x1D}, {'A', 0x1E}, {'S', 0x1F},
                {'D', 0x20}, {'F', 0x21}, {'G', 0x22}, {'H', 0x23}, {'J', 0x24}, {'K', 0x25}, {'L', 0x26},
                {';', 0x27}, {'\'', 0x28}, {'`', 0x29}, {KEY_LEFT_SHIFT, 0x2A}, {'\\', 0x2B}, {'Z', 0x2C},
                {'X', 0x2D}, {'C', 0x2E}, {'V', 0x2F}, {'B', 0x30}, {'N', 0x31}, {'M', 0x32}, {',', 0x33},
                {'.', 0x34}, {'/', 0x35}, {KEY_RIGHT_SHIFT, 0x36}, {KEY_KP_MULTIPLY, 0x37}, {KEY_LEFT_ALT, 0x38},
                {KEY_SPACE, 0x39}, {KEY_CAPS_LOCK, 0x3A}, {KEY_NUM_LOCK, 0x45}, {KEY_SCROLL_LOCK, 0x46},
                {KEY_KP_0 + 7, 0x47}, {KEY_KP_0 + 8, 0x48}, {KEY_KP_0 + 9, 0x49}, {KEY_KP_SUBTRACT, 0x4A},
                {KEY_KP_0 + 4, 0x4B}, {KEY_KP_0 + 5, 0x4C}, {KEY_KP_0 + 6, 0x4D}, {KEY_KP_ADD, 0x4E},
                {KEY_KP_0 + 1, 0x4F}, {KEY_KP_0 + 2, 0x50}, {KEY_KP_0 + 3, 0x51}, {KEY_KP_0, 0x52},
                {KEY_KP_DECIMAL, 0x53}, {KEY_WORLD_1, 0x56}, {KEY_F1 + 10, 0x57}, {KEY_F1 + 11, 0x58},
                {KEY_KP_ENTER, 0xE01C}, {KEY_RIGHT_CONTROL, 0xE01D}, {KEY_KP_DIVIDE, 0xE035},
                {KEY_PRINT_SCREEN, 0xE037}, {KEY_RIGHT_ALT, 0xE038}, {KEY_HOME, 0xE047}, {KEY_UP, 0xE048},
                {KEY_PAGE_UP, 0xE049}, {KEY_LEFT, 0xE04B}, {KEY_RIGHT, 0xE04D}, {KEY_END, 0xE04F},
                {KEY_DOWN, 0xE050}, {KEY_PAGE_DOWN, 0xE051}, {KEY_INSERT, 0xE052}, {KEY_DELETE, 0xE053},
                {KEY_LEFT_SUPER, 0xE05B}, {KEY_RIGHT_SUPER, 0xE05C}, {KEY_MENU, 0xE05D}
        };
        for (int[] e : sc) SCANCODE[e[0]] = e[1];
        for (int i = 0; i < 10; i++) SCANCODE[KEY_F1 + i] = 0x3B + i;
    }

    private KeyMaps() {
    }

    /** @return X11 keysym for the GLFW key, 0 if unmapped */
    public static int keysym(int glfwKey) {
        return glfwKey >= 0 && glfwKey < KEYSYM.length ? KEYSYM[glfwKey] : 0;
    }

    /** @return set-1 make code (E0 keys come back as 0xE0xx), 0 if unmapped */
    public static int scancode(int glfwKey) {
        return glfwKey >= 0 && glfwKey < SCANCODE.length ? SCANCODE[glfwKey] : 0;
    }

    /** Scan code bytes for a press/release. Pause is the odd one: make-only sequence, nothing on release. */
    public static int[] scancodeBytes(int glfwKey, boolean down) {
        if (glfwKey == KEY_PAUSE) {
            return down ? new int[]{0xE1, 0x1D, 0x45, 0xE1, 0x9D, 0xC5} : new int[0];
        }
        int code = scancode(glfwKey);
        if (code == 0) return new int[0];
        int base = code & 0xFF;
        if (!down) base |= 0x80;
        return (code & 0xE000) == 0xE000 ? new int[]{0xE0, base} : new int[]{base};
    }

    /** Code for QEMU's extended key event: the XT code, high bit set for E0 keys. */
    public static int qemuKeycode(int glfwKey) {
        int code = scancode(glfwKey);
        if (code == 0) return 0;
        return (code & 0xE000) == 0xE000 ? 0x80 | (code & 0x7F) : code;
    }

    /**
     * VT220 bytes for a non-printable key on a terminal guest. {@code null} means the key's text
     * comes in through {@code charTyped} instead.
     */
    public static byte[] terminalSequence(int glfwKey, boolean ctrl, boolean alt, boolean appCursor) {
        if (ctrl && glfwKey >= KEY_A && glfwKey <= KEY_Z) {
            byte b = (byte) (glfwKey - KEY_A + 1);
            return alt ? new byte[]{0x1B, b} : new byte[]{b};
        }
        if (ctrl) {
            switch (glfwKey) {
                case '[': return new byte[]{0x1B};
                case '\\': return new byte[]{0x1C};
                case ']': return new byte[]{0x1D};
                case KEY_SPACE: case '2': return new byte[]{0x00};
                default: break;
            }
        }
        String cursorPrefix = appCursor ? "\033O" : "\033[";
        String s = switch (glfwKey) {
            case KEY_ENTER, KEY_KP_ENTER -> "\r";
            case KEY_BACKSPACE -> "\u007f";
            case KEY_TAB -> "\t";
            case KEY_ESCAPE -> "\033";
            case KEY_UP -> cursorPrefix + "A";
            case KEY_DOWN -> cursorPrefix + "B";
            case KEY_RIGHT -> cursorPrefix + "C";
            case KEY_LEFT -> cursorPrefix + "D";
            case KEY_HOME -> "\033[H";
            case KEY_END -> "\033[F";
            case KEY_INSERT -> "\033[2~";
            case KEY_DELETE -> "\033[3~";
            case KEY_PAGE_UP -> "\033[5~";
            case KEY_PAGE_DOWN -> "\033[6~";
            case KEY_F1 -> "\033OP";
            case KEY_F1 + 1 -> "\033OQ";
            case KEY_F1 + 2 -> "\033OR";
            case KEY_F1 + 3 -> "\033OS";
            case KEY_F1 + 4 -> "\033[15~";
            case KEY_F1 + 5 -> "\033[17~";
            case KEY_F1 + 6 -> "\033[18~";
            case KEY_F1 + 7 -> "\033[19~";
            case KEY_F1 + 8 -> "\033[20~";
            case KEY_F1 + 9 -> "\033[21~";
            case KEY_F1 + 10 -> "\033[23~";
            case KEY_F1 + 11 -> "\033[24~";
            default -> null;
        };
        if (s == null) return null;
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        if (alt && bytes.length == 1) return new byte[]{0x1B, bytes[0]};
        return bytes;
    }

    public static boolean isModifier(int glfwKey) {
        return glfwKey >= KEY_LEFT_SHIFT && glfwKey <= KEY_RIGHT_SUPER;
    }

    public static boolean isCtrl(int glfwKey) {
        return glfwKey == KEY_LEFT_CONTROL || glfwKey == KEY_RIGHT_CONTROL;
    }

    public static boolean isAlt(int glfwKey) {
        return glfwKey == KEY_LEFT_ALT || glfwKey == KEY_RIGHT_ALT;
    }
}
