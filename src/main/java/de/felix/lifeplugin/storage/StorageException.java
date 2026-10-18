package de.felix.lifeplugin.storage;

/** Storage failures are explicit: callers must never reset data after an I/O failure. */
public final class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) { super(message, cause); }
    public StorageException(String message) { super(message); }
}
