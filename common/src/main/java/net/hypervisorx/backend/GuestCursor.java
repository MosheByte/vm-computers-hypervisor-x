package net.hypervisorx.backend;

/**
 * Guest mouse cursor, as the hypervisor reports it. Once the guest has its drivers (VMware Tools, QEMU)
 * it stops painting the cursor into the frame. The display server tells us the shape, if it's shown and
 * where the guest moved it, and the viewer draws it. Display reader thread writes, stream pump reads,
 * so everything here is synchronized.
 */
public final class GuestCursor {
    /** Biggest shape we send to players, anything larger gets cropped. */
    public static final int MAX_SIZE = 64;

    private int width;
    private int height;
    private int hotX;
    private int hotY;
    private int[] argb = new int[0];
    private boolean visible = true;
    private int shapeVersion;
    private int warpX;
    private int warpY;
    private int warpVersion;

    public synchronized void setShape(int w, int h, int hotX, int hotY, int[] argb) {
        int cw = Math.min(MAX_SIZE, w);
        int ch = Math.min(MAX_SIZE, h);
        int[] crop = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(argb, y * w, crop, y * cw, cw);
        this.width = cw;
        this.height = ch;
        this.hotX = Math.min(hotX, Math.max(0, cw - 1));
        this.hotY = Math.min(hotY, Math.max(0, ch - 1));
        this.argb = crop;
        shapeVersion++;
    }

    public synchronized void setVisible(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        shapeVersion++;
    }

    /** Guest moved its own cursor (games keep it centred), so viewers follow. */
    public synchronized void warp(int x, int y) {
        warpX = x;
        warpY = y;
        warpVersion++;
    }

    public synchronized int shapeVersion() {
        return shapeVersion;
    }

    public synchronized int warpVersion() {
        return warpVersion;
    }

    public synchronized boolean hasShape() {
        return width > 0 && height > 0;
    }

    /** Copy of the current shape: {width, height, hotX, hotY, visible (0/1)}, then the ARGB pixels. */
    public synchronized int[] snapshot() {
        int[] out = new int[5 + argb.length];
        out[0] = width;
        out[1] = height;
        out[2] = hotX;
        out[3] = hotY;
        out[4] = visible ? 1 : 0;
        System.arraycopy(argb, 0, out, 5, argb.length);
        return out;
    }

    public synchronized int[] warpPosition() {
        return new int[]{warpX, warpY};
    }
}
