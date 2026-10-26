package net.hypervisorx.backend.virtualbox;

/** Public hook so we can stop our {@code vboxwebsrv} when the world closes. */
public final class VirtualBoxShutdown {
    private VirtualBoxShutdown() {
    }

    public static void stopWebService() {
        VboxWebService.stop();
    }
}
