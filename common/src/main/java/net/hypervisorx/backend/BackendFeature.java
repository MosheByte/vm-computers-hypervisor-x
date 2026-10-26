package net.hypervisorx.backend;

public enum BackendFeature {
    /** Can boot ISOs and persistent virtual disks. */
    DISKS,
    /** Guest audio plays in the world. */
    AUDIO,
    /** Point-in-time snapshots. */
    SNAPSHOTS,
    /** Saves to disk when the world closes, picks back up on the next power-on. */
    SUSPEND,
    /** Virtual serial port for the Redstone GPIO card. */
    GPIO,
    /** Can plug in USB flash drive images. */
    USB_DRIVES,
    /** Keyboard + mouse input to the guest. */
    INPUT,
    /** Works with nothing else installed. */
    ZERO_INSTALL,
    /** Mirrors a host window (view only). */
    HOST_WINDOWS
}
