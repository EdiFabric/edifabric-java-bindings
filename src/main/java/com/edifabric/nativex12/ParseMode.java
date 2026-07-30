package com.edifabric.nativex12;

/** mode argument for parse and start_split. */
public enum ParseMode {
    /** Transaction-set JSON only. */
    JSON(1),
    /** JSON plus a validation report. */
    JSON_VALIDATE(2),
    /** JSON plus validation and a 999/997/TA1 acknowledgment. */
    JSON_VALIDATE_ACK(3);

    private final int value;

    ParseMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
