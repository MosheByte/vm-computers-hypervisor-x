package net.hypervisorx.vm.display;

/**
 * One compressed framebuffer tile, as it goes over the wire.
 *
 * @param x    left pixel
 * @param y    top pixel
 * @param w    width in pixels (at most {@link Framebuffer#TILE})
 * @param h    height in pixels
 * @param mode bit flags: {@link #DELTA}, {@link #RGB565}, {@link #DEFLATE}
 * @param data payload: RGB888 or RGB565 pixels (can be an XOR delta vs the previous version)
 */
public record EncodedTile(int x, int y, int w, int h, int mode, byte[] data) {
    public static final int DELTA = 1;
    public static final int RGB565 = 2;
    public static final int DEFLATE = 4;

    public int wireSize() {
        return data.length + 12;
    }
}
