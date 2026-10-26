package net.hypervisorx;

/**
 * Lets common code (items used on the logical client) open client screens without referencing
 * client classes. The client entrypoint installs the implementation.
 */
public final class ClientBridge {
    public interface Impl {
        void openStore();

        void openSettings();

        void openLibrary();
    }

    private static volatile Impl impl;

    private ClientBridge() {
    }

    public static void install(Impl i) {
        impl = i;
    }

    public static void openStore() {
        Impl i = impl;
        if (i != null) i.openStore();
    }

    public static void openSettings() {
        Impl i = impl;
        if (i != null) i.openSettings();
    }

    public static void openLibrary() {
        Impl i = impl;
        if (i != null) i.openLibrary();
    }
}
