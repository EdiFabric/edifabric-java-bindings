package com.edifabric.nativex12;

/** A payload produced by a split stream. */
public final class SplitPart {
    private final byte[] payload;
    private final int offset;
    private final boolean last;

    public SplitPart(byte[] payload, int offset, boolean last) {
        this.payload = payload;
        this.offset = offset;
        this.last = last;
    }

    /** The UTF-8 result bytes. */
    public byte[] getPayload() {
        return payload;
    }

    /** Offset of the validation section inside the payload. */
    public int getOffset() {
        return offset;
    }

    /** True when this is the final payload. */
    public boolean isLast() {
        return last;
    }
}
