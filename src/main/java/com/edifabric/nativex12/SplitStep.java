package com.edifabric.nativex12;

/** One step of a split stream. */
public final class SplitStep {
    private final int size;
    private final int offset;
    private final boolean last;

    public SplitStep(int size, int offset, boolean last) {
        this.size = size;
        this.offset = offset;
        this.last = last;
    }

    /** Bytes available from getResult; 0 when this step produced nothing. */
    public int getSize() {
        return size;
    }

    /** Offset of the validation section inside the result. */
    public int getOffset() {
        return offset;
    }

    /** True when this is the final step. */
    public boolean isLast() {
        return last;
    }
}
