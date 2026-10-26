package net.hypervisorx.backend.embedded;

import net.hypervisorx.vm.display.Framebuffer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * xterm-compatible terminal emulator (a VT100/VT220 subset) that draws straight into a
 * {@link Framebuffer}. Handles everything BusyBox, vi, top, less and the Linux kernel throw at it:
 * cursor addressing, scroll regions, insert/delete, SGR with 16/256/true colour, the alt screen,
 * DEC line drawing, UTF-8, device status reports and OSC strings. The HyperVisor X GPIO bridge
 * rides on OSC 1337 ({@code printf '\033]1337;GPIO;SET;north;15\a'}).
 */
public final class VtTerminal {
    public interface Host {
        /** Bytes the terminal answers with (DSR, DA). These go back into the guest's input. */
        void reply(byte[] data);

        /** OSC (Operating System Command) string, minus the leading ESC ] and trailing BEL/ST. */
        void osc(String payload);

        void bell();
    }

    private static final int DEFAULT_FG = 256;
    private static final int DEFAULT_BG = 257;
    private static final int ATTR_FG_MASK = 0x1FF;
    private static final int ATTR_BG_SHIFT = 9;
    private static final int BOLD = 1 << 18;
    private static final int UNDERLINE = 1 << 19;
    private static final int INVERSE = 1 << 20;
    private static final int DIM = 1 << 21;
    private static final int TRUECOLOR_FG = 1 << 22;
    private static final int TRUECOLOR_BG = 1 << 23;
    private static final int DEFAULT_ATTR = DEFAULT_FG | (DEFAULT_BG << ATTR_BG_SHIFT);

    private static final int[] PALETTE = buildPalette();
    private static final int COLOR_DEFAULT_FG = 0xD8DEE9;
    private static final int COLOR_DEFAULT_BG = 0x0B0F17;

    private final int cols;
    private final int rows;
    private final Host host;

    private int[] chr;
    private int[] attr;
    private int[] trueFg;
    private int[] trueBg;
    private int[] mainChr;
    private int[] mainAttr;
    private boolean altScreen;

    private final int[] renderedChr;
    private final int[] renderedAttr;
    private final int[] renderedColors;

    private int cx;
    private int cy;
    private boolean wrapPending;
    private int savedX;
    private int savedY;
    private int savedAttr = DEFAULT_ATTR;
    private int top;
    private int bottom;
    private int curAttr = DEFAULT_ATTR;
    private int curTrueFg;
    private int curTrueBg;
    private boolean cursorVisible = true;
    private boolean autowrap = true;
    private boolean originMode;
    private boolean insertMode;
    private boolean appCursorKeys;
    private boolean lineDrawingG0;
    private boolean lineDrawingG1;
    private boolean shiftOut;
    private final boolean[] tabStops;

    // parser
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, OSC_ESC = 4, CHARSET0 = 5, CHARSET1 = 6, HASH = 7, DCS = 8, DCS_ESC = 9;
    private int state = GROUND;
    private final int[] params = new int[16];
    private int paramCount;
    private boolean paramStarted;
    private char privateMarker;
    private char intermediate;
    private final StringBuilder osc = new StringBuilder();
    private int utfRemaining;
    private int utfCodepoint;
    private int lastPrinted = ' ';

    private String title = "";
    private long lastBlinkToggle;
    private boolean blinkOn = true;

    public VtTerminal(int cols, int rows, Host host) {
        this.cols = cols;
        this.rows = rows;
        this.host = host;
        int n = cols * rows;
        chr = new int[n];
        attr = new int[n];
        trueFg = new int[n];
        trueBg = new int[n];
        mainChr = chr;
        mainAttr = attr;
        renderedChr = new int[n];
        renderedAttr = new int[n];
        renderedColors = new int[n];
        Arrays.fill(renderedChr, -1);
        tabStops = new boolean[cols];
        reset();
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized boolean applicationCursorKeys() {
        return appCursorKeys;
    }

    public synchronized void reset() {
        Arrays.fill(chr, ' ');
        Arrays.fill(attr, DEFAULT_ATTR);
        if (altScreen) {
            chr = mainChr;
            attr = mainAttr;
            altScreen = false;
            Arrays.fill(chr, ' ');
            Arrays.fill(attr, DEFAULT_ATTR);
        }
        cx = cy = 0;
        wrapPending = false;
        top = 0;
        bottom = rows - 1;
        curAttr = DEFAULT_ATTR;
        cursorVisible = true;
        autowrap = true;
        originMode = false;
        insertMode = false;
        appCursorKeys = false;
        lineDrawingG0 = lineDrawingG1 = shiftOut = false;
        Arrays.fill(tabStops, false);
        for (int i = 0; i < cols; i += 8) tabStops[i] = true;
        state = GROUND;
    }

    /** Feed it the raw bytes the guest wrote. */
    public synchronized void write(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            feed(data[i] & 0xFF);
        }
    }

    public synchronized void write(int b) {
        feed(b & 0xFF);
    }

    public synchronized void writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (byte value : b) feed(value & 0xFF);
    }

    private void feed(int b) {
        // control chars get handled in every state, except inside OSC/DCS strings
        if (state != OSC && state != DCS && state != OSC_ESC && state != DCS_ESC && b < 0x20) {
            control(b);
            return;
        }
        switch (state) {
            case GROUND -> ground(b);
            case ESC -> escape(b);
            case CSI -> csi(b);
            case OSC -> {
                if (b == 0x07) {
                    finishOsc();
                } else if (b == 0x1B) {
                    state = OSC_ESC;
                } else if (osc.length() < 4096) {
                    osc.append((char) b);
                }
            }
            case OSC_ESC -> {
                if (b == '\\') {
                    finishOsc();
                } else {
                    state = GROUND;
                }
            }
            case DCS -> {
                if (b == 0x1B) state = DCS_ESC;
                else if (b == 0x07) state = GROUND;
            }
            case DCS_ESC -> state = b == '\\' ? GROUND : DCS;
            case CHARSET0 -> {
                lineDrawingG0 = b == '0';
                state = GROUND;
            }
            case CHARSET1 -> {
                lineDrawingG1 = b == '0';
                state = GROUND;
            }
            case HASH -> {
                if (b == '8') { // DECALN
                    Arrays.fill(chr, 'E');
                    Arrays.fill(attr, DEFAULT_ATTR);
                }
                state = GROUND;
            }
            default -> state = GROUND;
        }
    }

    private void control(int b) {
        switch (b) {
            case 0x07 -> host.bell();
            case 0x08 -> {
                if (cx > 0) cx--;
                wrapPending = false;
            }
            case 0x09 -> {
                int x = cx + 1;
                while (x < cols - 1 && !tabStops[x]) x++;
                cx = Math.min(cols - 1, x);
                wrapPending = false;
            }
            case 0x0A, 0x0B, 0x0C -> lineFeed();
            case 0x0D -> {
                cx = 0;
                wrapPending = false;
            }
            case 0x0E -> shiftOut = true;
            case 0x0F -> shiftOut = false;
            case 0x18, 0x1A -> state = GROUND;
            case 0x1B -> {
                state = ESC;
                intermediate = 0;
            }
            default -> { }
        }
    }

    private void ground(int b) {
        int cp;
        if (utfRemaining > 0) {
            if ((b & 0xC0) == 0x80) {
                utfCodepoint = (utfCodepoint << 6) | (b & 0x3F);
                if (--utfRemaining > 0) return;
                cp = utfCodepoint;
            } else {
                utfRemaining = 0;
                cp = 0xFFFD;
                put(cp);
                ground(b);
                return;
            }
        } else if (b < 0x80) {
            cp = b;
        } else if ((b & 0xE0) == 0xC0) {
            utfCodepoint = b & 0x1F;
            utfRemaining = 1;
            return;
        } else if ((b & 0xF0) == 0xE0) {
            utfCodepoint = b & 0x0F;
            utfRemaining = 2;
            return;
        } else if ((b & 0xF8) == 0xF0) {
            utfCodepoint = b & 0x07;
            utfRemaining = 3;
            return;
        } else {
            cp = 0xFFFD;
        }
        if (cp == 0x7F) return;
        boolean lineDrawing = shiftOut ? lineDrawingG1 : lineDrawingG0;
        if (lineDrawing && cp >= 0x5F && cp <= 0x7E) {
            cp = DEC_LINE_DRAWING[cp - 0x5F];
        }
        put(cp);
    }

    private static final int[] DEC_LINE_DRAWING = {
            0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1, 0x2424, 0x240B,
            0x2518, 0x2510, 0x250C, 0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C,
            0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7
    };

    private void put(int cp) {
        if (wrapPending) {
            if (autowrap) {
                cx = 0;
                lineFeed();
            }
            wrapPending = false;
        }
        if (insertMode) {
            insertChars(1);
        }
        int i = cy * cols + cx;
        chr[i] = cp;
        attr[i] = curAttr;
        trueFg[i] = curTrueFg;
        trueBg[i] = curTrueBg;
        lastPrinted = cp;
        if (cx == cols - 1) {
            wrapPending = true;
        } else {
            cx++;
        }
    }

    private void lineFeed() {
        wrapPending = false;
        if (cy == bottom) {
            scrollUp(top, bottom, 1);
        } else if (cy < rows - 1) {
            cy++;
        }
    }

    private void reverseIndex() {
        wrapPending = false;
        if (cy == top) {
            scrollDown(top, bottom, 1);
        } else if (cy > 0) {
            cy--;
        }
    }

    private void scrollUp(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        if (n <= 0) return;
        System.arraycopy(chr, (from + n) * cols, chr, from * cols, (to - from + 1 - n) * cols);
        System.arraycopy(attr, (from + n) * cols, attr, from * cols, (to - from + 1 - n) * cols);
        System.arraycopy(trueFg, (from + n) * cols, trueFg, from * cols, (to - from + 1 - n) * cols);
        System.arraycopy(trueBg, (from + n) * cols, trueBg, from * cols, (to - from + 1 - n) * cols);
        clearRows(to - n + 1, to);
    }

    private void scrollDown(int from, int to, int n) {
        n = Math.min(n, to - from + 1);
        if (n <= 0) return;
        System.arraycopy(chr, from * cols, chr, (from + n) * cols, (to - from + 1 - n) * cols);
        System.arraycopy(attr, from * cols, attr, (from + n) * cols, (to - from + 1 - n) * cols);
        System.arraycopy(trueFg, from * cols, trueFg, (from + n) * cols, (to - from + 1 - n) * cols);
        System.arraycopy(trueBg, from * cols, trueBg, (from + n) * cols, (to - from + 1 - n) * cols);
        clearRows(from, from + n - 1);
    }

    private int blankAttr() {
        // erased cells keep the current bg colour (that's what xterm does) but drop every other flag
        return DEFAULT_FG | (curAttr & (0x1FF << ATTR_BG_SHIFT)) | (curAttr & TRUECOLOR_BG);
    }

    private void clearRows(int from, int to) {
        int a = blankAttr();
        for (int r = from; r <= to; r++) {
            Arrays.fill(chr, r * cols, (r + 1) * cols, ' ');
            Arrays.fill(attr, r * cols, (r + 1) * cols, a);
            Arrays.fill(trueBg, r * cols, (r + 1) * cols, curTrueBg);
        }
    }

    private void clearRange(int from, int toExclusive) {
        int a = blankAttr();
        Arrays.fill(chr, from, toExclusive, ' ');
        Arrays.fill(attr, from, toExclusive, a);
        Arrays.fill(trueBg, from, toExclusive, curTrueBg);
    }

    private void insertChars(int n) {
        int rowStart = cy * cols;
        n = Math.min(n, cols - cx);
        for (int[] arr : new int[][]{chr, attr, trueFg, trueBg}) {
            System.arraycopy(arr, rowStart + cx, arr, rowStart + cx + n, cols - cx - n);
        }
        clearRange(rowStart + cx, rowStart + cx + n);
    }

    private void deleteChars(int n) {
        int rowStart = cy * cols;
        n = Math.min(n, cols - cx);
        for (int[] arr : new int[][]{chr, attr, trueFg, trueBg}) {
            System.arraycopy(arr, rowStart + cx + n, arr, rowStart + cx, cols - cx - n);
        }
        clearRange(rowStart + cols - n, rowStart + cols);
    }

    private void escape(int b) {
        state = GROUND;
        switch (b) {
            case '[' -> {
                state = CSI;
                paramCount = 0;
                paramStarted = false;
                privateMarker = 0;
                intermediate = 0;
                Arrays.fill(params, 0);
            }
            case ']' -> {
                state = OSC;
                osc.setLength(0);
            }
            case 'P', 'X', '^', '_' -> state = DCS;
            case '(' -> state = CHARSET0;
            case ')' -> state = CHARSET1;
            case '#' -> state = HASH;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'D' -> lineFeed();
            case 'E' -> {
                cx = 0;
                lineFeed();
            }
            case 'H' -> tabStops[cx] = true;
            case 'M' -> reverseIndex();
            case 'c' -> {
                reset();
                Arrays.fill(chr, ' ');
            }
            case '=', '>' -> { }
            default -> { }
        }
    }

    private void saveCursor() {
        savedX = cx;
        savedY = cy;
        savedAttr = curAttr;
    }

    private void restoreCursor() {
        cx = Math.min(savedX, cols - 1);
        cy = Math.min(savedY, rows - 1);
        curAttr = savedAttr;
        wrapPending = false;
    }

    private int param(int i, int def) {
        if (i >= paramCount) return def;
        int v = params[i];
        return v == 0 ? def : v;
    }

    private void csi(int b) {
        if (b >= '0' && b <= '9') {
            if (paramCount == 0) paramCount = 1;
            int idx = paramCount - 1;
            params[idx] = Math.min(65535, params[idx] * 10 + (b - '0'));
            paramStarted = true;
            return;
        }
        if (b == ';' || b == ':') {
            if (paramCount == 0) paramCount = 1;
            if (paramCount < params.length) paramCount++;
            return;
        }
        if (b == '?' || b == '>' || b == '<' || b == '=') {
            privateMarker = (char) b;
            return;
        }
        if (b >= 0x20 && b <= 0x2F) {
            intermediate = (char) b;
            return;
        }
        state = GROUND;
        if (b < 0x40 || b > 0x7E) return;
        dispatchCsi((char) b);
    }

    private void moveTo(int x, int y) {
        int minY = originMode ? top : 0;
        int maxY = originMode ? bottom : rows - 1;
        cx = Math.max(0, Math.min(cols - 1, x));
        cy = Math.max(minY, Math.min(maxY, y));
        wrapPending = false;
    }

    private void dispatchCsi(char f) {
        if (privateMarker == '?') {
            if (f == 'h' || f == 'l') {
                boolean set = f == 'h';
                for (int i = 0; i < Math.max(1, paramCount); i++) privateMode(params[i], set);
            }
            return;
        }
        if (privateMarker != 0) {
            if (f == 'c' && privateMarker == '>') host.reply("\033[>0;10;0c".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        switch (f) {
            case '@' -> insertChars(param(0, 1));
            case 'A' -> moveTo(cx, cy - param(0, 1));
            case 'B', 'e' -> moveTo(cx, cy + param(0, 1));
            case 'C', 'a' -> moveTo(cx + param(0, 1), cy);
            case 'D' -> moveTo(cx - param(0, 1), cy);
            case 'E' -> moveTo(0, cy + param(0, 1));
            case 'F' -> moveTo(0, cy - param(0, 1));
            case 'G', '`' -> moveTo(param(0, 1) - 1, cy);
            case 'H', 'f' -> moveTo(param(1, 1) - 1, (originMode ? top : 0) + param(0, 1) - 1);
            case 'I' -> {
                for (int n = param(0, 1); n > 0; n--) control(0x09);
            }
            case 'J' -> {
                int mode = paramCount > 0 ? params[0] : 0;
                int pos = cy * cols + cx;
                switch (mode) {
                    case 0 -> clearRange(pos, rows * cols);
                    case 1 -> clearRange(0, pos + 1);
                    default -> clearRange(0, rows * cols);
                }
            }
            case 'K' -> {
                int mode = paramCount > 0 ? params[0] : 0;
                int rowStart = cy * cols;
                switch (mode) {
                    case 0 -> clearRange(rowStart + cx, rowStart + cols);
                    case 1 -> clearRange(rowStart, rowStart + cx + 1);
                    default -> clearRange(rowStart, rowStart + cols);
                }
            }
            case 'L' -> {
                if (cy >= top && cy <= bottom) scrollDown(cy, bottom, param(0, 1));
            }
            case 'M' -> {
                if (cy >= top && cy <= bottom) scrollUp(cy, bottom, param(0, 1));
            }
            case 'P' -> deleteChars(param(0, 1));
            case 'S' -> scrollUp(top, bottom, param(0, 1));
            case 'T' -> scrollDown(top, bottom, param(0, 1));
            case 'X' -> {
                int start = cy * cols + cx;
                clearRange(start, Math.min(start + param(0, 1), (cy + 1) * cols));
            }
            case 'b' -> {
                for (int n = Math.min(param(0, 1), 1024); n > 0; n--) put(lastPrinted);
            }
            case 'c' -> host.reply("\033[?1;2c".getBytes(StandardCharsets.US_ASCII));
            case 'd' -> moveTo(cx, (originMode ? top : 0) + param(0, 1) - 1);
            case 'g' -> {
                int mode = paramCount > 0 ? params[0] : 0;
                if (mode == 0) tabStops[cx] = false;
                else if (mode == 3) Arrays.fill(tabStops, false);
            }
            case 'h', 'l' -> {
                if (paramCount > 0 && params[0] == 4) insertMode = f == 'h';
            }
            case 'm' -> sgr();
            case 'n' -> {
                int mode = paramCount > 0 ? params[0] : 0;
                if (mode == 5) {
                    host.reply("\033[0n".getBytes(StandardCharsets.US_ASCII));
                } else if (mode == 6) {
                    int row = originMode ? cy - top + 1 : cy + 1;
                    host.reply(("\033[" + row + ";" + (cx + 1) + "R").getBytes(StandardCharsets.US_ASCII));
                }
            }
            case 'r' -> {
                int t = param(0, 1) - 1;
                int bt = param(1, rows) - 1;
                if (t < bt && bt < rows) {
                    top = t;
                    bottom = bt;
                    moveTo(0, originMode ? top : 0);
                }
            }
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 't' -> { }
            default -> { }
        }
    }

    private void privateMode(int mode, boolean set) {
        switch (mode) {
            case 1 -> appCursorKeys = set;
            case 6 -> {
                originMode = set;
                moveTo(0, set ? top : 0);
            }
            case 7 -> autowrap = set;
            case 25 -> cursorVisible = set;
            case 47, 1047, 1049 -> setAltScreen(set, mode == 1049);
            default -> { }
        }
    }

    private void setAltScreen(boolean enable, boolean saveCursor) {
        if (enable == altScreen) return;
        if (enable) {
            if (saveCursor) saveCursor();
            chr = new int[cols * rows];
            attr = new int[cols * rows];
            Arrays.fill(chr, ' ');
            Arrays.fill(attr, DEFAULT_ATTR);
            altScreen = true;
        } else {
            chr = mainChr;
            attr = mainAttr;
            altScreen = false;
            if (saveCursor) restoreCursor();
        }
    }

    private void sgr() {
        if (paramCount == 0) {
            curAttr = DEFAULT_ATTR;
            return;
        }
        for (int i = 0; i < paramCount; i++) {
            int p = params[i];
            switch (p) {
                case 0 -> curAttr = DEFAULT_ATTR;
                case 1 -> curAttr |= BOLD;
                case 2 -> curAttr |= DIM;
                case 4 -> curAttr |= UNDERLINE;
                case 7 -> curAttr |= INVERSE;
                case 21, 22 -> curAttr &= ~(BOLD | DIM);
                case 24 -> curAttr &= ~UNDERLINE;
                case 27 -> curAttr &= ~INVERSE;
                case 39 -> curAttr = (curAttr & ~ATTR_FG_MASK & ~TRUECOLOR_FG) | DEFAULT_FG;
                case 49 -> curAttr = (curAttr & ~(0x1FF << ATTR_BG_SHIFT) & ~TRUECOLOR_BG) | (DEFAULT_BG << ATTR_BG_SHIFT);
                case 38, 48 -> {
                    boolean fg = p == 38;
                    if (i + 1 < paramCount && params[i + 1] == 5 && i + 2 < paramCount) {
                        setColor(fg, params[i + 2] & 0xFF);
                        i += 2;
                    } else if (i + 1 < paramCount && params[i + 1] == 2 && i + 4 < paramCount) {
                        int rgb = (Math.min(255, params[i + 2]) << 16) | (Math.min(255, params[i + 3]) << 8) | Math.min(255, params[i + 4]);
                        if (fg) {
                            curAttr |= TRUECOLOR_FG;
                            curTrueFg = rgb;
                        } else {
                            curAttr |= TRUECOLOR_BG;
                            curTrueBg = rgb;
                        }
                        i += 4;
                    }
                }
                default -> {
                    if (p >= 30 && p <= 37) setColor(true, p - 30);
                    else if (p >= 40 && p <= 47) setColor(false, p - 40);
                    else if (p >= 90 && p <= 97) setColor(true, p - 90 + 8);
                    else if (p >= 100 && p <= 107) setColor(false, p - 100 + 8);
                }
            }
        }
    }

    private void setColor(boolean fg, int index) {
        if (fg) {
            curAttr = (curAttr & ~ATTR_FG_MASK & ~TRUECOLOR_FG) | index;
        } else {
            curAttr = (curAttr & ~(0x1FF << ATTR_BG_SHIFT) & ~TRUECOLOR_BG) | (index << ATTR_BG_SHIFT);
        }
    }

    private void finishOsc() {
        state = GROUND;
        String s = osc.toString();
        osc.setLength(0);
        if (s.startsWith("0;") || s.startsWith("2;")) {
            title = s.substring(2);
        }
        host.osc(s);
    }

    // ---------------------------------------------------------------- rendering

    private static int[] buildPalette() {
        int[] p = new int[256];
        int[] base = {
                0x1B1F27, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xD8DEE9,
                0x5C6370, 0xFF7A85, 0xB5E890, 0xFFD68A, 0x82C4FF, 0xDE9BF0, 0x7FD7E0, 0xFFFFFF
        };
        System.arraycopy(base, 0, p, 0, 16);
        int[] steps = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++) {
            p[16 + i] = (steps[(i / 36) % 6] << 16) | (steps[(i / 6) % 6] << 8) | steps[i % 6];
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            p[232 + i] = (v << 16) | (v << 8) | v;
        }
        return p;
    }

    private static int resolve(int index, boolean fg, boolean bold) {
        if (index == DEFAULT_FG) return COLOR_DEFAULT_FG;
        if (index == DEFAULT_BG) return COLOR_DEFAULT_BG;
        if (fg && bold && index < 8) index += 8;
        return PALETTE[index & 0xFF];
    }

    private static int dim(int rgb) {
        return ((rgb >> 1) & 0x7F7F7F);
    }

    /**
     * Draws every cell that changed since the last call into the framebuffer.
     * The framebuffer has to be at least {@code cols * cellWidth} x {@code rows * cellHeight}.
     */
    public synchronized void render(Framebuffer fb, GlyphFont font, int originX, int originY) {
        long now = System.currentTimeMillis();
        if (now - lastBlinkToggle >= 530) {
            blinkOn = !blinkOn;
            lastBlinkToggle = now;
        }
        int cw = font.cellWidth();
        int ch = font.cellHeight();
        int cursorIndex = cursorVisible && blinkOn ? cy * cols + cx : -1;
        boolean any = false;
        synchronized (fb.lock()) {
            int[] px = fb.rawPixelsLocked();
            int stride = fb.width();
            if (originX + cols * cw > stride || originY + rows * ch > fb.height()) return;
            for (int i = 0; i < chr.length; i++) {
                int a = attr[i];
                boolean cursor = i == cursorIndex;
                int fgColor = (a & TRUECOLOR_FG) != 0 ? trueFg[i] : resolve(a & ATTR_FG_MASK, true, (a & BOLD) != 0);
                int bgColor = (a & TRUECOLOR_BG) != 0 ? trueBg[i] : resolve((a >>> ATTR_BG_SHIFT) & 0x1FF, false, false);
                if ((a & DIM) != 0) fgColor = dim(fgColor);
                if (((a & INVERSE) != 0) ^ cursor) {
                    int t = fgColor;
                    fgColor = bgColor;
                    bgColor = t;
                }
                int colorKey = fgColor * 31 + bgColor;
                int stamp = a ^ (cursor ? 0x40000000 : 0);
                if (renderedChr[i] == chr[i] && renderedAttr[i] == stamp && renderedColors[i] == colorKey) continue;
                renderedChr[i] = chr[i];
                renderedAttr[i] = stamp;
                renderedColors[i] = colorKey;
                any = true;
                byte[] glyph = font.glyph(chr[i]);
                int x0 = originX + (i % cols) * cw;
                int y0 = originY + (i / cols) * ch;
                boolean underline = (a & UNDERLINE) != 0;
                for (int gy = 0; gy < ch; gy++) {
                    int bits = glyph[gy] & 0xFF;
                    if (underline && gy == ch - 2) bits = 0xFF;
                    int row = (y0 + gy) * stride + x0;
                    for (int gx = 0; gx < cw; gx++) {
                        px[row + gx] = (bits & (0x80 >>> gx)) != 0 ? fgColor : bgColor;
                    }
                }
                fb.markDirtyLockedExternal(x0, y0, cw, ch);
            }
        }
        if (any) fb.frameCompleted();
    }

    /** Plain-text dump of the visible screen (tests and the server console mirror use it). */
    public synchronized String screenText() {
        StringBuilder sb = new StringBuilder(rows * (cols + 1));
        for (int r = 0; r < rows; r++) {
            int end = cols;
            while (end > 0 && chr[r * cols + end - 1] == ' ') end--;
            for (int c = 0; c < end; c++) sb.appendCodePoint(chr[r * cols + c]);
            sb.append('\n');
        }
        return sb.toString();
    }
}
