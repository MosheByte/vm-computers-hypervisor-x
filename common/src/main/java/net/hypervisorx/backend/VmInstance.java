package net.hypervisorx.backend;

import net.hypervisorx.vm.display.Framebuffer;

import java.nio.file.Path;

/**
 * Handle to one running machine. Everything here is thread-safe and doesn't block unless the doc
 * says so. Input calls just queue work for the backend's own I/O thread.
 */
public interface VmInstance extends AutoCloseable {
    enum State { STARTING, RUNNING, STOPPING, STOPPED, ERROR }

    interface Listener {
        void stateChanged(State state, String message);

        /** Mono signed 16-bit PCM at {@link #AUDIO_RATE} Hz. */
        default void audio(short[] samples, int count) {
        }
    }

    int AUDIO_RATE = 22050;

    Framebuffer framebuffer();

    State state();

    String statusMessage();

    void setListener(Listener listener);

    /** Physical key down/up as GLFW key codes (keyboard layout doesn't matter). */
    void key(int glfwKey, boolean down);

    /** Unicode text the user typed (terminal-style guests use this). */
    default void text(int codepoint) {
    }

    /** Absolute pointer pos in framebuffer pixels + RFB button mask (1 left, 2 middle, 4 right). */
    void pointer(int x, int y, int buttons);

    /** Mouse wheel: positive = up. */
    void wheel(int x, int y, int buttons, int clicks);

    /** Sends Ctrl+Alt+Delete without the host grabbing it first. */
    void ctrlAltDelete();

    /** ACPI power button (asks the guest to shut down nicely). */
    void powerButton();

    void reset();

    /** Hard power off. Returns once the machine is gone. */
    void forceOff();

    /** Saves state to disk and stops. Returns {@code false} if the backend can't do that. */
    default boolean suspend() {
        return false;
    }

    /** Takes a named snapshot. */
    default void snapshot(String name) throws BackendException {
        throw new BackendException("Snapshots are not supported by this backend");
    }

    /** Guest's own cursor, if the display server sends it separately from the picture. Null otherwise. */
    default GuestCursor cursor() {
        return null;
    }

    /** Puts a disc in the optical drive, or ejects it when {@code null}. */
    default void changeMedia(Path iso) throws BackendException {
        throw new BackendException("Changing media at runtime is not supported by this backend; power the machine off first");
    }

    /** Serial port wired to the Redstone GPIO card, or {@code null}. */
    default SerialLink gpioLink() {
        return null;
    }

    @Override
    void close();
}
