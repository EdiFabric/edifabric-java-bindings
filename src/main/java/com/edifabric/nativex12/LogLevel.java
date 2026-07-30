package com.edifabric.nativex12;

/** min_level argument for init_logger. */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFORMATION(2),
    WARNING(3),
    ERROR(4),
    ALL(5);

    private final int value;

    LogLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
