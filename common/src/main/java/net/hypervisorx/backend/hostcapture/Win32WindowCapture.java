package net.hypervisorx.backend.hostcapture;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GDI capture through PrintWindow(PW_RENDERFULLCONTENT), so covered and GPU-rendered windows still work. */
final class Win32WindowCapture implements AutoCloseable {
    interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean PrintWindow(WinDef.HWND hwnd, WinDef.HDC hdcBlt, int flags);

        boolean IsIconic(WinDef.HWND hwnd);
    }

    private static final int PW_CLIENTONLY = 1;
    private static final int PW_RENDERFULLCONTENT = 2;

    record WindowInfo(WinDef.HWND hwnd, String title) {
    }

    private final WinDef.HWND hwnd;
    private WinDef.HDC memDc;
    private WinDef.HBITMAP bitmap;
    private WinNT.HANDLE previous;
    private Memory buffer;
    private int width;
    private int height;

    Win32WindowCapture(WinDef.HWND hwnd) {
        this.hwnd = hwnd;
    }

    static List<WindowInfo> listWindows() {
        List<WindowInfo> out = new ArrayList<>();
        User32 u = User32.INSTANCE;
        char[] text = new char[512];
        u.EnumWindows((hwnd, data) -> {
            if (!u.IsWindowVisible(hwnd)) return true;
            int len = u.GetWindowText(hwnd, text, text.length);
            if (len <= 0) return true;
            WinDef.RECT r = new WinDef.RECT();
            u.GetClientRect(hwnd, r);
            if (r.right - r.left < 64 || r.bottom - r.top < 64) return true;
            out.add(new WindowInfo(hwnd, new String(text, 0, len)));
            return true;
        }, null);
        return out;
    }

    static WindowInfo find(String filter) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        for (WindowInfo w : listWindows()) {
            if (f.isEmpty() || w.title().toLowerCase(Locale.ROOT).contains(f)) return w;
        }
        return null;
    }

    boolean isValid() {
        return User32.INSTANCE.IsWindow(hwnd);
    }

    /** Grabs the client area into {@code out} as 0x00RRGGBB. Returns {width, height}, or null. */
    int[] capture(int[][] out) {
        User32 u = User32.INSTANCE;
        GDI32 g = GDI32.INSTANCE;
        if (User32Ext.INSTANCE.IsIconic(hwnd)) return null;
        WinDef.RECT r = new WinDef.RECT();
        if (!u.GetClientRect(hwnd, r)) return null;
        int w = Math.max(1, r.right - r.left);
        int h = Math.max(1, r.bottom - r.top);
        WinDef.HDC windowDc = u.GetDC(hwnd);
        if (windowDc == null) return null;
        try {
            if (memDc == null || w != width || h != height) {
                release();
                memDc = g.CreateCompatibleDC(windowDc);
                bitmap = g.CreateCompatibleBitmap(windowDc, w, h);
                previous = g.SelectObject(memDc, bitmap);
                buffer = new Memory((long) w * h * 4);
                width = w;
                height = h;
            }
            if (!User32Ext.INSTANCE.PrintWindow(hwnd, memDc, PW_CLIENTONLY | PW_RENDERFULLCONTENT)) return null;
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = w;
            bmi.bmiHeader.biHeight = -h;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            int lines = g.GetDIBits(memDc, bitmap, 0, h, buffer, bmi, WinGDI.DIB_RGB_COLORS);
            if (lines <= 0) return null;
            if (out[0] == null || out[0].length < w * h) out[0] = new int[w * h];
            buffer.read(0, out[0], 0, w * h);
            int[] px = out[0];
            for (int i = 0; i < w * h; i++) px[i] &= 0xFFFFFF;
            return new int[]{w, h};
        } finally {
            u.ReleaseDC(hwnd, windowDc);
        }
    }

    private void release() {
        GDI32 g = GDI32.INSTANCE;
        if (memDc != null) {
            if (previous != null) g.SelectObject(memDc, previous);
            if (bitmap != null) g.DeleteObject(bitmap);
            g.DeleteDC(memDc);
        }
        memDc = null;
        bitmap = null;
        previous = null;
    }

    @Override
    public void close() {
        release();
    }
}
