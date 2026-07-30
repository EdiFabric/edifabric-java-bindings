package com.edifabric.nativex12;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

/**
 * One-to-one JNA declarations for {@code c-abi-edifabric_x12_tools.h}.
 * Prefer {@link EdiFabricX12} over calling these directly.
 */
interface EdiFabricNative extends Library {

    /* Lifecycle / logging */

    int init_logger(byte[] pathUtf8, int pathLen, int minLevel);

    int shutdown_logger();

    int clear_cache();

    /* Licensing */

    int install_license(byte[] serial, int serialLen);

    int get_app_version(IntByReference appVersion);

    int get_token(byte[] serial, int serialLen, byte[] output, int outputCapacity, IntByReference outputLength);

    int validate_token(byte[] token, int tokenLen);

    int set_token(byte[] token, int tokenLen);

    int get_token_expiration(LongByReference expirationUtc);

    int set_serial(byte[] serial, int serialLen);

    /* Model map */

    int set_map(byte[] map, int mapLength);

    /* Parse / split / build / merge */

    int parse(
            byte[] input, int inputLength,
            int mode,
            byte[] config, int configLength,
            byte[] output, int outputCapacity,
            IntByReference outputLength, IntByReference outputOffset);

    int start_split(Pointer input, int inputLength, int mode, Pointer config, int configLength);

    int split(int[] resultSize, int[] resultOffset, byte[] last);

    int build(byte[] input, int inputLength, byte[] postfix, byte[] output, int outputCapacity, IntByReference outputLength);

    int start_merge(Pointer input, int inputLength);

    int merge(IntByReference resultSize);

    int get_result(byte[] buffer, int bufferSize);

    /* Error messages */

    Pointer get_error(int errorCode);

    void free_error(Pointer pointer);
}
