package com.edifabric.nativex12;

/** Output of a parse call. */
public final class ParseResult {
    private final String output;
    private final int offset;

    public ParseResult(String output, int offset) {
        this.output = output;
        this.offset = offset;
    }

    /** The full UTF-8 payload returned by the native library. */
    public String getOutput() {
        return output;
    }

    /** Where the validation and acknowledgment section starts; 0 in mode 1. */
    public int getOffset() {
        return offset;
    }

    /** The transaction-set JSON portion of the output. */
    public String getTransactions() {
        return offset > 0 ? output.substring(0, offset) : output;
    }

    /** The validation and acknowledgment JSON, or an empty string in mode 1. */
    public String getReport() {
        return offset > 0 ? output.substring(offset) : "";
    }
}
