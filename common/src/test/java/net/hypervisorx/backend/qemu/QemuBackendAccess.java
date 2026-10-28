package net.hypervisorx.backend.qemu;

/** Lets tests reach the package-private QEMU helpers. */
public final class QemuBackendAccess {
    private QemuBackendAccess() {
    }

    public static String privateLoopback() {
        return QemuBackend.privateLoopback();
    }

    public static String randomPassword() {
        return QemuBackend.randomPassword();
    }
}
