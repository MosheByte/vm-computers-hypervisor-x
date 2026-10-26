package net.hypervisorx.backend;

public class BackendException extends Exception {
    public BackendException(String message) {
        super(message);
    }

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
