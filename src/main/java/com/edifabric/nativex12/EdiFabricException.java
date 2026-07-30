package com.edifabric.nativex12;

/** Thrown when a native call returns a non-zero status code. */
public final class EdiFabricException extends RuntimeException {
    private final int code;
    private final String nativeMessage;
    private final String api;

    public EdiFabricException(int code, String message, String api) {
        super(api == null || api.isEmpty()
                ? "error " + code + ": " + message
                : api + ": error " + code + ": " + message);
        this.code = code;
        this.nativeMessage = message;
        this.api = api == null ? "" : api;
    }

    public int getCode() {
        return code;
    }

    /** The message reported by get_error, without the wrapper prefix. */
    public String getNativeMessage() {
        return nativeMessage;
    }

    /** The C entry point that failed. */
    public String getApi() {
        return api;
    }
}
