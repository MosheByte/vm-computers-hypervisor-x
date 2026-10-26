package net.hypervisorx.backend.embedded;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixed-cell bitmap font, loaded from the HVXF table that {@code tools/bdf2hvxf.py} spits out.
 * We bundle Spleen 8x16 (BSD-2-Clause, see hypervisorx/fonts/SPLEEN-LICENSE.txt).
 */
public final class GlyphFont {
    private static volatile GlyphFont defaultFont;

    private final int cellWidth;
    private final int cellHeight;
    private final Map<Integer, byte[]> glyphs;
    private final byte[] fallback;

    private GlyphFont(int cellWidth, int cellHeight, Map<Integer, byte[]> glyphs) {
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.glyphs = glyphs;
        byte[] q = glyphs.get((int) '?');
        this.fallback = q != null ? q : new byte[cellHeight];
    }

    public static GlyphFont load(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] magic = new byte[4];
        data.readFully(magic);
        if (magic[0] != 'H' || magic[1] != 'V' || magic[2] != 'X' || magic[3] != 'F') {
            throw new IOException("Not an HVXF font");
        }
        int w = data.readUnsignedByte();
        int h = data.readUnsignedByte();
        int count = data.readInt();
        Map<Integer, byte[]> map = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            int cp = data.readInt();
            byte[] rows = new byte[h];
            data.readFully(rows);
            map.put(cp, rows);
        }
        return new GlyphFont(w, h, map);
    }

    public static GlyphFont defaultFont() {
        GlyphFont font = defaultFont;
        if (font == null) {
            synchronized (GlyphFont.class) {
                font = defaultFont;
                if (font == null) {
                    try (InputStream in = GlyphFont.class.getResourceAsStream("/hypervisorx/fonts/spleen-8x16.hvxf")) {
                        if (in == null) throw new IOException("missing font resource");
                        font = load(in);
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to load the embedded terminal font", e);
                    }
                    defaultFont = font;
                }
            }
        }
        return font;
    }

    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    public byte[] glyph(int codepoint) {
        byte[] g = glyphs.get(codepoint);
        return g != null ? g : (codepoint == ' ' ? new byte[cellHeight] : fallback);
    }

    public boolean has(int codepoint) {
        return glyphs.containsKey(codepoint);
    }
}
