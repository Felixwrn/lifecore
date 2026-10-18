package de.felix.lifeplugin.network;

import java.io.IOException;

/** Status only: never includes potentially sensitive URLs or response bodies. */
public final class HttpStatusException extends IOException {
    private final int statusCode;

    public HttpStatusException(int statusCode) {
        super("Remote server returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
