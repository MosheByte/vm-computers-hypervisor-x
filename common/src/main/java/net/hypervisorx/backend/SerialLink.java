package net.hypervisorx.backend;

import java.util.function.Consumer;

/** Byte stream between the mod and a guest serial port (COM1 / ttyS0). */
public interface SerialLink {
    void send(byte[] data);

    /** Gets the bytes the guest writes. Called on the link's reader thread. */
    void setReceiver(Consumer<byte[]> receiver);

    boolean isConnected();
}
