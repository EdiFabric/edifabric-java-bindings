package com.edifabric.nativex12;

/** Library-level error codes. Validation codes come from the engine. */
public enum ErrorCode {
    INSUFFICIENT_CAPACITY(1),
    UNKNOWN(501),
    NO_CONNECTION(502),
    INVALID_MAP(503),
    INCORRECT_INPUT(611),
    LOGGER_INIT(612),
    MAP_DESERIALIZE(613),
    INCORRECT_CAPACITY(614),
    MAP_NOT_SET(615),
    INCORRECT_MODE(616),
    NO_JSON(617),
    VALIDATION_UNAVAILABLE(618),
    VALIDATION_SERIALIZE(619),
    INCORRECT_TOKEN(620),
    CONFIG_DESERIALIZE(621),
    SPLIT_SEGMENT_ID_MISSING(622),
    SPLIT_NOT_STARTED(623),
    NO_RESULT(624),
    RESULT_SIZE_MISMATCH(625),
    MERGE_NOT_STARTED(626),
    INCORRECT_OUTPUT_POINTER(627),
    INCORRECT_SERIAL(628),
    LICENSE_NOT_INSTALLED(629),
    APP_VERSION_EXCEEDED(630),
    TOKEN_EXPIRED(631),
    TOKEN_MISSING(632),
    MAX_LICENSES_EXCEEDED(633),
    LICENSE_SNAPSHOT_MISSING(634),
    LICENSE_NOT_SET(635);

    private final int value;

    ErrorCode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
