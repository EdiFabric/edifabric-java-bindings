package com.edifabric.nativex12;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Java JNA bindings for ediFabric Native X12 (see {@code c-abi-edifabric_x12_tools.h}).
 *
 * <p>Loads the platform shared library ({@code edifabric-x12-tools.dll} / {@code .so} /
 * {@code .dylib}) and calls the wrappers below. All payloads cross the boundary as UTF-8
 * byte buffers. Native return codes: {@code 0} = success, {@code 1} = InsufficientCapacity
 * (grow-and-retry), anything else is an error whose message comes from {@link #getError(int)}.
 *
 * <p>The wrappers handle grow-and-retry internally and throw {@link EdiFabricException} on failure.
 */
public final class EdiFabricX12 {
    public static final int SUCCESS = 0;
    public static final int INSUFFICIENT_CAPACITY = 1;

    private static final Object LOCK = new Object();
    private static volatile EdiFabricNative lib;
    private static volatile String resolvedLibraryPath;
    private static volatile String libraryPathHint;
    private static volatile boolean freeErrorMissing;

    /**
     * Native start_split / start_merge keep pointers into the input buffers for the
     * duration of the stream. JNA's temporary copies of {@code byte[]} are freed when
     * the call returns, so these must stay alive as {@link Memory} until the stream ends.
     */
    private static Memory splitInputMemory;
    private static Memory splitConfigMemory;
    private static Memory mergeInputMemory;

    private EdiFabricX12() {
    }

    /**
     * Explicit path to the native library, either the file itself or its folder.
     * Set this before the first call. {@code EDIFABRIC_X12_LIB} is used when it is null.
     */
    public static void setLibraryPath(String path) {
        libraryPathHint = path;
    }

    /** The library file that was actually loaded, once resolution has happened. */
    public static String getResolvedLibraryPath() {
        return resolvedLibraryPath;
    }

    /**
     * Load and bind the native library.
     *
     * @param path may be the library file or the folder containing it; null to search
     *             {@code EDIFABRIC_X12_LIB}, this class's folder, its parent, and the cwd
     * @return the loaded JNA library instance
     */
    public static EdiFabricNative loadLibrary(String path) {
        synchronized (LOCK) {
            if (lib != null && path == null && libraryPathHint == null) {
                return lib;
            }
            if (path != null) {
                libraryPathHint = path;
            }

            List<Path> candidates = candidates(libraryPathHint);
            IOException lastError = null;
            for (Path candidate : candidates) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    String absolute = candidate.toAbsolutePath().normalize().toString();
                    EdiFabricNative loaded = Native.load(absolute, EdiFabricNative.class);
                    lib = loaded;
                    resolvedLibraryPath = absolute;
                    return loaded;
                } catch (UnsatisfiedLinkError error) {
                    lastError = new IOException(error.getMessage(), error);
                }
            }

            StringBuilder tried = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) {
                    tried.append(", ");
                }
                tried.append(candidates.get(i));
            }
            throw new UnsatisfiedLinkError(
                    "Could not load " + platformFileName() + ". Tried: " + tried
                            + ". Last error: " + (lastError == null ? "none" : lastError.getMessage()));
        }
    }

    /** Load using the current path hint / environment / search paths. */
    public static EdiFabricNative loadLibrary() {
        return loadLibrary(null);
    }

    private static EdiFabricNative lib() {
        EdiFabricNative current = lib;
        return current != null ? current : loadLibrary();
    }

    static String platformFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "edifabric-x12-tools.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "edifabric-x12-tools.dylib";
        }
        return "edifabric-x12-tools.so";
    }

    private static List<Path> candidates(String explicit) {
        String name = platformFileName();
        List<Path> found = new ArrayList<>();
        addHint(found, explicit, name);
        addHint(found, System.getenv("EDIFABRIC_X12_LIB"), name);

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        found.add(cwd.resolve(name));

        Path codeSource = codeSourceDirectory();
        if (codeSource != null) {
            found.add(codeSource.resolve(name));
            Path parent = codeSource.getParent();
            for (int level = 0; level < 6 && parent != null; level++) {
                found.add(parent.resolve(name));
                parent = parent.getParent();
            }
        }
        return found;
    }

    private static void addHint(List<Path> found, String hint, String name) {
        if (hint == null || hint.trim().isEmpty()) {
            return;
        }
        Path path = Paths.get(hint.trim());
        if (Files.isDirectory(path)) {
            found.add(path.resolve(name));
        } else {
            found.add(path);
        }
    }

    private static Path codeSourceDirectory() {
        try {
            return Paths.get(EdiFabricX12.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .getParent();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] utf8(String data) {
        if (data == null) {
            return new byte[0];
        }
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] utf8(byte[] data) {
        return data == null ? new byte[0] : data;
    }

    /* ------------------------------------------------------------------ */
    /* Errors                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Release a string returned by the raw {@code get_error} export.
     * {@link #getError(int)} already does this; call {@code freeError} only when you
     * invoke the raw export yourself.
     */
    public static void freeError(Pointer pointer) {
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            return;
        }
        if (!freeErrorMissing) {
            try {
                lib().free_error(pointer);
                return;
            } catch (UnsatisfiedLinkError ignored) {
                freeErrorMissing = true;
            }
        }
        Native.free(Pointer.nativeValue(pointer));
    }

    /** Return the message for an error code, releasing the native string. */
    public static String getError(int errorCode) {
        Pointer pointer = lib().get_error(errorCode);
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            return "Unknown error " + errorCode;
        }
        try {
            String value = pointer.getString(0, "UTF-8");
            return value == null || value.isEmpty() ? "Unknown error " + errorCode : value;
        } finally {
            freeError(pointer);
        }
    }

    public static String getError(ErrorCode errorCode) {
        return getError(errorCode.getValue());
    }

    /** Throw {@link EdiFabricException} unless {@code rc} is success. */
    public static void check(int rc, String api) {
        if (rc != SUCCESS) {
            throw new EdiFabricException(rc, getError(rc), api == null ? "" : api);
        }
    }

    private static byte[] withGrowth(int capacity, String api, GrowthCall call) {
        int current = Math.max(1, capacity);
        IntByReference outLength = new IntByReference(0);
        while (true) {
            byte[] buffer = new byte[current];
            int rc = call.invoke(buffer, current, outLength);
            if (rc == INSUFFICIENT_CAPACITY) {
                current = outLength.getValue() > current ? outLength.getValue() : current * 2;
                continue;
            }
            check(rc, api);
            int length = outLength.getValue();
            byte[] result = new byte[length];
            System.arraycopy(buffer, 0, result, 0, length);
            return result;
        }
    }

    @FunctionalInterface
    private interface GrowthCall {
        int invoke(byte[] buffer, int capacity, IntByReference outLength);
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle and logging                                               */
    /* ------------------------------------------------------------------ */

    /** Write a log file at path; minLevel is 0=Trace through 4=Error. */
    public static void initLogger(String path, LogLevel minLevel) {
        byte[] bytes = utf8(path);
        check(lib().init_logger(bytes, bytes.length, minLevel.getValue()), "init_logger");
    }

    public static void initLogger(String path) {
        initLogger(path, LogLevel.INFORMATION);
    }

    /** Flush and stop the logger. */
    public static void shutdownLogger() {
        check(lib().shutdown_logger(), "shutdown_logger");
    }

    /** Reset the model map, split/merge state, last result, license, and logger. */
    public static void clearCache() {
        check(lib().clear_cache(), "clear_cache");
    }

    /* ------------------------------------------------------------------ */
    /* Licensing                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Cache a token for runtime authorization against the license server.
     * Retrieves a new token if the cached token expires within the specified
     * number of seconds.
     */
    public static void ensureToken(String serial, int seconds) {
        byte[] bytes = utf8(serial);
        check(lib().ensure_token(bytes, bytes.length, seconds), "ensure_token");
    }

    /** Return the library application version. */
    public static int getAppVersion() {
        IntByReference version = new IntByReference(0);
        check(lib().get_app_version(version), "get_app_version");
        return version.getValue();
    }

    /** Fetch a signed license token for the serial. Requires internet access. */
    public static String getToken(String serial, int capacity) {
        byte[] serialBytes = utf8(serial);
        byte[] token = withGrowth(capacity, "get_token",
                (buffer, cap, outLength) -> lib().get_token(serialBytes, serialBytes.length, buffer, cap, outLength));
        return new String(token, StandardCharsets.UTF_8);
    }

    public static String getToken(String serial) {
        return getToken(serial, 4096);
    }

    /** Validate a token without caching it. */
    public static void validateToken(String token) {
        byte[] bytes = utf8(token);
        check(lib().validate_token(bytes, bytes.length), "validate_token");
    }

    /** Cache a token for this process. */
    public static void setToken(String token) {
        byte[] bytes = utf8(token);
        check(lib().set_token(bytes, bytes.length), "set_token");
    }

    /** Return token expiry as .NET UTC ticks; 0 when no token is set. */
    public static long getTokenExpirationTicks() {
        LongByReference expiration = new LongByReference(0);
        check(lib().get_token_expiration(expiration), "get_token_expiration");
        return expiration.getValue();
    }

    /**
     * Return token expiry as an Instant, or null when no token is set.
     * .NET ticks are 100 ns intervals since 0001-01-01 UTC.
     */
    public static Instant getTokenExpiration() {
        long ticks = getTokenExpirationTicks();
        if (ticks == 0L) {
            return null;
        }
        long unixEpochTicks = 621355968000000000L;
        long nanos = (ticks - unixEpochTicks) * 100L;
        long seconds = nanos / 1_000_000_000L;
        long nanoAdjustment = nanos % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, nanoAdjustment).atOffset(ZoneOffset.UTC).toInstant();
    }

    /** Cache a serial for runtime authorization against the license server. */
    public static void setSerial(String serial) {
        byte[] bytes = utf8(serial);
        check(lib().set_serial(bytes, bytes.length), "set_serial");
    }

    /* ------------------------------------------------------------------ */
    /* Model map                                                           */
    /* ------------------------------------------------------------------ */

    /** Load the template map JSON. Call once before parse or start_split. */
    public static void setMap(String mapJson) {
        byte[] bytes = utf8(mapJson);
        check(lib().set_map(bytes, bytes.length), "set_map");
    }

    public static void setMap(byte[] mapJson) {
        byte[] bytes = utf8(mapJson);
        check(lib().set_map(bytes, bytes.length), "set_map");
    }

    /* ------------------------------------------------------------------ */
    /* Parse, split, build, merge                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Parse a whole interchange and return the output and offset.
     *
     * <p>offset is 0 for mode 1; for modes 2 and 3 it marks where the validation and
     * acknowledgment section begins inside output.
     */
    public static ParseResult parse(String edi, ParseMode mode, String config, int capacity) {
        return parse(utf8(edi), mode, config == null ? null : utf8(config), capacity);
    }

    public static ParseResult parse(String edi, ParseMode mode, String config) {
        return parse(edi, mode, config, 0);
    }

    public static ParseResult parse(String edi, ParseMode mode) {
        return parse(edi, mode, null, 0);
    }

    public static ParseResult parse(String edi) {
        return parse(edi, ParseMode.JSON, null, 0);
    }

    public static ParseResult parse(byte[] edi, ParseMode mode, byte[] config, int capacity) {
        Objects.requireNonNull(edi, "edi");
        Objects.requireNonNull(mode, "mode");
        byte[] configBytes = utf8(config);
        int current = capacity > 0 ? capacity : growthEstimate(edi.length);
        IntByReference outLength = new IntByReference(0);
        IntByReference outOffset = new IntByReference(0);

        while (true) {
            byte[] buffer = new byte[current];
            int rc = lib().parse(
                    edi, edi.length,
                    mode.getValue(),
                    configBytes, configBytes.length,
                    buffer, current,
                    outLength, outOffset);
            if (rc == INSUFFICIENT_CAPACITY) {
                current = outLength.getValue() > current ? outLength.getValue() : current * 2;
                continue;
            }
            check(rc, "parse");
            int length = outLength.getValue();
            return new ParseResult(new String(buffer, 0, length, StandardCharsets.UTF_8), outOffset.getValue());
        }
    }

    private static Memory toMemory(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Memory memory = new Memory(bytes.length);
        memory.write(0, bytes, 0, bytes.length);
        return memory;
    }

    /** Begin a streaming split. config must contain a split section with segment_id. */
    public static void startSplit(String edi, ParseMode mode, String config) {
        startSplit(utf8(edi), mode, config == null ? null : utf8(config));
    }

    public static void startSplit(byte[] edi, ParseMode mode, byte[] config) {
        Objects.requireNonNull(edi, "edi");
        Objects.requireNonNull(mode, "mode");
        byte[] configBytes = utf8(config);
        splitInputMemory = toMemory(edi);
        splitConfigMemory = toMemory(configBytes);
        Pointer configPointer = splitConfigMemory == null ? Pointer.NULL : splitConfigMemory;
        int configLength = splitConfigMemory == null ? 0 : (int) splitConfigMemory.size();
        check(
                lib().start_split(splitInputMemory, edi.length, mode.getValue(), configPointer, configLength),
                "start_split");
    }

    /**
     * Advance the split stream and return size, offset, and isLast.
     * Fetch the payload with {@link #getResult(int)} whenever size is greater than zero.
     */
    public static SplitStep split() {
        int[] size = new int[1];
        int[] offset = new int[1];
        byte[] last = new byte[1];
        check(lib().split(size, offset, last), "split");
        SplitStep step = new SplitStep(size[0], offset[0], last[0] != 0);
        if (step.isLast()) {
            splitInputMemory = null;
            splitConfigMemory = null;
        }
        return step;
    }

    /**
     * Build X12 EDI from transaction-set JSON.
     *
     * @param postfix appended after each segment terminator, for example {@code "\r\n"}.
     *                Pass null for compact single-line output.
     */
    public static String build(String jsonText, String postfix, int capacity) {
        byte[] input = utf8(jsonText);
        // Native postfix is const char* — must be null-terminated.
        final byte[] postfixBytes;
        if (postfix == null) {
            postfixBytes = null;
        } else {
            byte[] raw = utf8(postfix);
            byte[] terminated = new byte[raw.length + 1];
            System.arraycopy(raw, 0, terminated, 0, raw.length);
            postfixBytes = terminated;
        }
        int current = capacity > 0 ? capacity : Math.max(4096, input.length);
        byte[] edi = withGrowth(current, "build",
                (buffer, cap, outLength) -> lib().build(input, input.length, postfixBytes, buffer, cap, outLength));
        return new String(edi, StandardCharsets.UTF_8);
    }

    public static String build(String jsonText, String postfix) {
        return build(jsonText, postfix, 0);
    }

    public static String build(String jsonText) {
        return build(jsonText, null, 0);
    }

    /** Begin a streaming merge from a full interchange JSON document. */
    public static void startMerge(String jsonText) {
        byte[] bytes = utf8(jsonText);
        mergeInputMemory = toMemory(bytes);
        check(lib().start_merge(mergeInputMemory, bytes.length), "start_merge");
    }

    /** Return the size of the next segment, or 0 at the end of the stream. */
    public static int merge() {
        IntByReference size = new IntByReference(0);
        check(lib().merge(size), "merge");
        int value = size.getValue();
        if (value == 0) {
            mergeInputMemory = null;
        }
        return value;
    }

    /** Copy the last split or merge result. size must match the reported size. */
    public static byte[] getResult(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be zero or greater");
        }
        byte[] buffer = new byte[size];
        check(lib().get_result(buffer, size), "get_result");
        return buffer;
    }

    /* ------------------------------------------------------------------ */
    /* Convenience iterators                                               */
    /* ------------------------------------------------------------------ */

    /** Yield (payload, offset, isLast) for every split step that produces data. */
    public static Iterable<SplitPart> iterSplit(String edi, ParseMode mode, String config) {
        return () -> new SplitIterator(utf8(edi), mode, config == null ? null : utf8(config));
    }

    /** Yield each X12 segment produced by a merge stream. */
    public static Iterable<byte[]> iterMerge(String jsonText) {
        return () -> new MergeIterator(jsonText);
    }

    private static final class SplitIterator implements Iterator<SplitPart> {
        private SplitPart next;
        private boolean started;
        private boolean finished;
        private final byte[] edi;
        private final ParseMode mode;
        private final byte[] config;

        private SplitIterator(byte[] edi, ParseMode mode, byte[] config) {
            this.edi = edi;
            this.mode = mode;
            this.config = config;
        }

        private void ensureStarted() {
            if (!started) {
                startSplit(edi, mode, config);
                started = true;
                advance();
            }
        }

        private void advance() {
            next = null;
            while (!finished) {
                SplitStep step = split();
                if (step.getSize() > 0) {
                    next = new SplitPart(getResult(step.getSize()), step.getOffset(), step.isLast());
                }
                if (step.isLast()) {
                    finished = true;
                }
                if (next != null) {
                    return;
                }
            }
        }

        @Override
        public boolean hasNext() {
            ensureStarted();
            return next != null;
        }

        @Override
        public SplitPart next() {
            ensureStarted();
            if (next == null) {
                throw new NoSuchElementException();
            }
            SplitPart current = next;
            advance();
            return current;
        }
    }

    private static final class MergeIterator implements Iterator<byte[]> {
        private final String jsonText;
        private boolean started;
        private byte[] next;

        private MergeIterator(String jsonText) {
            this.jsonText = jsonText;
        }

        private void ensureStarted() {
            if (!started) {
                startMerge(jsonText);
                started = true;
                advance();
            }
        }

        private void advance() {
            int size = merge();
            next = size == 0 ? null : getResult(size);
        }

        @Override
        public boolean hasNext() {
            ensureStarted();
            return next != null;
        }

        @Override
        public byte[] next() {
            ensureStarted();
            if (next == null) {
                throw new NoSuchElementException();
            }
            byte[] current = next;
            advance();
            return current;
        }
    }

    private static int growthEstimate(int inputLength) {
        long estimate = (long) inputLength * 12L;
        if (estimate < 4096L) {
            return 4096;
        }
        if (estimate > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) estimate;
    }

    /**
     * Direct access to the raw exports, for callers that want to own the
     * unmanaged memory themselves.
     */
    public static final class Raw {
        private Raw() {
        }

        /**
         * Calls get_error and returns the unmanaged pointer. Release it with
         * {@link EdiFabricX12#freeError(Pointer)}.
         */
        public static Pointer getError(int errorCode) {
            return lib().get_error(errorCode);
        }

        /** The library file name for the current platform. */
        public static String platformFileName() {
            return EdiFabricX12.platformFileName();
        }
    }
}
