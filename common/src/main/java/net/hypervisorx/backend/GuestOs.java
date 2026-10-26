package net.hypervisorx.backend;

import java.util.Locale;

/**
 * Guest OS family. Each backend maps it to its own type ids so the virtual hardware
 * (firmware, NIC, disk bus, clock) is what the installer expects.
 */
public enum GuestOs {
    LINUX("Linux (64-bit)", "Linux_64", "other5xlinux-64", false, false),
    WINDOWS_11("Windows 11", "Windows11_64", "windows11-64", true, true),
    WINDOWS_10("Windows 10", "Windows10_64", "windows9-64", true, false),
    WINDOWS_LEGACY("Windows 7 / XP", "Windows7_64", "windows7-64", true, false),
    REACTOS("ReactOS", "WindowsXP", "winxppro", true, false),
    DOS("DOS / FreeDOS", "DOS", "dos", false, false),
    OTHER("Other / hobby OS", "Other_64", "other-64", false, false);

    private final String displayName;
    private final String virtualBoxType;
    private final String vmwareType;
    private final boolean windowsClock;
    private final boolean needsEfiAndTpm;

    GuestOs(String displayName, String virtualBoxType, String vmwareType, boolean windowsClock, boolean needsEfiAndTpm) {
        this.displayName = displayName;
        this.virtualBoxType = virtualBoxType;
        this.vmwareType = vmwareType;
        this.windowsClock = windowsClock;
        this.needsEfiAndTpm = needsEfiAndTpm;
    }

    public String displayName() {
        return displayName;
    }

    public String virtualBoxType() {
        return virtualBoxType;
    }

    public String vmwareType() {
        return vmwareType;
    }

    /** Windows keeps the RTC in local time. */
    public boolean windowsClock() {
        return windowsClock;
    }

    public boolean needsEfiAndTpm() {
        return needsEfiAndTpm;
    }

    public boolean isWindowsFamily() {
        return this == WINDOWS_10 || this == WINDOWS_11 || this == WINDOWS_LEGACY || this == REACTOS;
    }

    public static GuestOs parse(String s) {
        if (s == null) return OTHER;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
