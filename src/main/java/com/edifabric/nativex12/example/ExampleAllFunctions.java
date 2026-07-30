package com.edifabric.nativex12.example;

import com.edifabric.nativex12.EdiFabricException;
import com.edifabric.nativex12.EdiFabricX12;
import com.edifabric.nativex12.ErrorCode;
import com.edifabric.nativex12.LogLevel;
import com.edifabric.nativex12.ParseMode;
import com.edifabric.nativex12.ParseResult;
import com.edifabric.nativex12.SplitPart;
import com.edifabric.nativex12.SplitStep;
import com.sun.jna.Pointer;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Walkthrough of every function in the ediFabric Native X12 C ABI.
 *
 * <p>Each section calls one group of entry points and prints the result, so the
 * output doubles as a reference for what each call returns.
 *
 * <pre>
 *   mvn -q exec:java
 *   mvn -q exec:java -Dexec.args="--serial YOUR_SERIAL"
 *   mvn -q exec:java -Dexec.args="--lib /path/to/edifabric-x12-tools.dll"
 * </pre>
 */
public final class ExampleAllFunctions {
    /** Free plan serial published in the ediFabric Native documentation. */
    private static final String DEFAULT_SERIAL = "bd96a836feca45cb91c86ee65d281f52";

    private static final Path ROOT = findRoot();
    private static final Path MAP_PATH = ROOT.resolve("map").resolve("map.json");
    private static final Path SAMPLE_EDI_PATH = ROOT.resolve("edi").resolve("837p.txt");
    private static final Path SAMPLE_EDI_INVALID_PATH = ROOT.resolve("edi").resolve("837p_error.txt");

    private static final String SAMPLE_EDI;
    private static final String SAMPLE_EDI_INVALID;

    private static final String PARSE_CONFIG = ""
            + "{"
            + "\"validate\":{"
            + "\"regex\":null,\"date_format\":null,\"time_format\":null,"
            + "\"skip_seq_count\":false,\"skip_hl_seq\":false,"
            + "\"snip_level\":4,\"max_errors\":100"
            + "},"
            + "\"ack\":{"
            + "\"supress_ta1\":false,\"ak901p\":false,"
            + "\"gen_for_valid\":true,\"gen997\":false"
            + "}"
            + "}";

    private static final String SPLIT_CONFIG = ""
            + "{"
            + "\"validate\":{"
            + "\"regex\":null,\"date_format\":null,\"time_format\":null,"
            + "\"skip_seq_count\":false,\"skip_hl_seq\":false,"
            + "\"snip_level\":4,\"max_errors\":100"
            + "},"
            + "\"ack\":{"
            + "\"supress_ta1\":false,\"ak901p\":false,"
            + "\"gen_for_valid\":true,\"gen997\":false"
            + "},"
            + "\"split\":{\"segment_id\":\"LX\",\"segment_depth\":6,\"loop_id\":\"2400\"}"
            + "}";

    static {
        try {
            SAMPLE_EDI = readUtf8(SAMPLE_EDI_PATH);
            SAMPLE_EDI_INVALID = readUtf8(SAMPLE_EDI_INVALID_PATH);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private ExampleAllFunctions() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        if (options == null) {
            System.exit(1);
            return;
        }

        section("Load: loadLibrary");
        EdiFabricX12.loadLibrary(options.libraryPath);
        System.out.println("  loaded " + EdiFabricX12.getResolvedLibraryPath());

        demoErrors();

        int exitCode = 0;
        try {
            demoLogging("edifabric.log");

            try {
                // The free and developer licenses do not support tokens. Authenticate only with serial.
                EdiFabricX12.setSerial(options.serial);
                System.out.println("  set_serial -> ok");

                demoSetLocalMap();

                String parsedJson = demoParse();

                demoSetOnlineMap(options.serial);

                demoParseValidate();
                demoParseAck();
                demoParseAckInvalid();
                demoSplit();
                demoBuild(parsedJson);
                demoMerge(parsedJson);
                demoIterators(parsedJson);

                demoTeardown();
            } catch (EdiFabricException | IOException exc) {
                System.err.println();
                System.err.println(exc.getMessage());
                System.err.println(
                        "Check that the serial is valid and that map/map.json resolves the "
                                + "transaction set to a local model file under map/.");
                exitCode = 1;
            }
        } finally {
            // Release the log file before exit (Windows).
            // On the success path demoTeardown already shut the logger down.
            try {
                EdiFabricX12.shutdownLogger();
            } catch (EdiFabricException ignored) {
                // already shut down
            }
        }

        if (exitCode != 0) {
            System.exit(exitCode);
            return;
        }

        section("Finished");
        System.out.println("Every entry point in c-abi-edifabric_x12_tools.h was called.");
    }

    /** get_error, free_error */
    private static void demoErrors() {
        section("Error messages: get_error, free_error");

        for (ErrorCode code : new ErrorCode[]{
                ErrorCode.INSUFFICIENT_CAPACITY,
                ErrorCode.MAP_NOT_SET,
                ErrorCode.LICENSE_NOT_SET
        }) {
            System.out.printf("  get_error(%3d) -> %s%n", code.getValue(), EdiFabricX12.getError(code));
        }

        // The raw export hands back a heap pointer that the caller owns.
        Pointer pointer = EdiFabricX12.Raw.getError(ErrorCode.TOKEN_EXPIRED.getValue());
        String message = pointer.getString(0, "UTF-8");
        EdiFabricX12.freeError(pointer);
        System.out.println("  raw get_error(631) -> " + message);
        System.out.println("  free_error(pointer) released the string");
    }

    /** init_logger, get_app_version */
    private static void demoLogging(String logPath) {
        section("Lifecycle: init_logger, get_app_version");

        EdiFabricX12.initLogger(logPath, LogLevel.TRACE);
        System.out.println("  init_logger -> logging to " + logPath);
        System.out.println("  get_app_version -> " + EdiFabricX12.getAppVersion());
    }

    /** set_map online */
    private static void demoSetOnlineMap(String serial) throws IOException {
        section("Model map: set_map online, default is the serial key");

        JSONObject modelMap = loadOnlineMap(serial);
        EdiFabricX12.setMap(modelMap.toString());
        String keys = modelMap.getJSONObject("maps").keySet().stream().sorted().collect(Collectors.joining(", "));
        if (keys.isEmpty()) {
            keys = "(none)";
        }
        System.out.println("  set_map <- " + ROOT.relativize(MAP_PATH));
        System.out.println("  default=" + modelMap.opt("default") + ", local maps=" + keys);
    }

    /** set_map local */
    private static void demoSetLocalMap() throws IOException {
        section("Model map: set_map local, default is blank");

        JSONObject modelMap = loadLocalMap();
        EdiFabricX12.setMap(modelMap.toString());
        String keys = modelMap.getJSONObject("maps").keySet().stream().sorted().collect(Collectors.joining(", "));
        if (keys.isEmpty()) {
            keys = "(none)";
        }
        System.out.println("  set_map <- " + ROOT.relativize(MAP_PATH));
        System.out.println("  default=" + modelMap.opt("default") + ", local maps=" + keys);
    }

    /** parse in mode 1 */
    private static String demoParse() {
        section("Parse: parse (mode 1, JSON only)");

        ParseResult result = EdiFabricX12.parse(SAMPLE_EDI, ParseMode.JSON);
        System.out.println("  " + result.getOutput().length() + " bytes of JSON, offset=" + result.getOffset());
        System.out.println("  " + preview(result.getOutput()));
        return result.getOutput();
    }

    /** parse in mode 2 */
    private static void demoParseValidate() {
        section("Parse: parse (mode 2, JSON + validation report)");

        ParseResult result = EdiFabricX12.parse(SAMPLE_EDI_INVALID, ParseMode.JSON_VALIDATE, PARSE_CONFIG);
        System.out.println("  " + result.getOutput().length() + " bytes total, validation starts at offset "
                + result.getOffset());
        System.out.println("  validation -> " + preview(result.getReport()));
    }

    /** parse in mode 3 */
    private static void demoParseAck() {
        section("Parse: parse (mode 3, JSON + validation + acknowledgment)");

        ParseResult result = EdiFabricX12.parse(SAMPLE_EDI, ParseMode.JSON_VALIDATE_ACK, PARSE_CONFIG);
        System.out.println("  " + result.getOutput().length() + " bytes total, report starts at offset "
                + result.getOffset());
        System.out.println("  report -> " + preview(result.getReport(), 600));
    }

    /** parse in mode 3 with invalid EDI */
    private static void demoParseAckInvalid() {
        section("Parse: parse (mode 3, JSON + validation + acknowledgment)");

        ParseResult result = EdiFabricX12.parse(SAMPLE_EDI_INVALID, ParseMode.JSON_VALIDATE_ACK, PARSE_CONFIG);
        System.out.println("  " + result.getOutput().length() + " bytes total, report starts at offset "
                + result.getOffset());
        System.out.println("  report -> " + preview(result.getReport(), 600));
    }

    /** start_split, split, get_result */
    private static void demoSplit() {
        section("Split: start_split, split, get_result");

        EdiFabricX12.startSplit(SAMPLE_EDI, ParseMode.JSON, SPLIT_CONFIG);
        int step = 0;
        while (true) {
            SplitStep current = EdiFabricX12.split();
            step++;
            System.out.println("  step " + step + ": size=" + current.getSize()
                    + " offset=" + current.getOffset() + " last=" + current.isLast());
            if (current.getSize() > 0) {
                String text = new String(EdiFabricX12.getResult(current.getSize()), StandardCharsets.UTF_8);
                System.out.println("    " + preview(text, 160));
            }
            if (current.isLast()) {
                break;
            }
        }
    }

    /** build */
    private static void demoBuild(String parsedJson) {
        section("Build: build");

        String edi = EdiFabricX12.build(parsedJson, "\r\n");
        System.out.println("  " + edi.length() + " bytes of X12");
        for (String line : edi.split("\r\n")) {
            if (!line.isEmpty()) {
                System.out.println("  " + line);
            }
        }
    }

    /** start_merge, merge, get_result */
    private static void demoMerge(String parsedJson) {
        section("Merge: start_merge, merge, get_result");

        EdiFabricX12.startMerge(parsedJson);
        int count = 0;
        while (true) {
            int size = EdiFabricX12.merge();
            if (size == 0) {
                break;
            }
            String segment = new String(EdiFabricX12.getResult(size), StandardCharsets.UTF_8);
            count++;
            System.out.printf("  segment %2d: %s%n", count, segment);
        }
        System.out.println("  merge produced " + count + " segments");
    }

    /** iterSplit, iterMerge convenience wrappers */
    private static void demoIterators(String parsedJson) {
        section("Convenience wrappers: iterSplit, iterMerge");

        List<SplitPart> parts = new ArrayList<>();
        for (SplitPart part : EdiFabricX12.iterSplit(SAMPLE_EDI, ParseMode.JSON, SPLIT_CONFIG)) {
            parts.add(part);
        }
        String sizes = parts.stream()
                .map(part -> Integer.toString(part.getPayload().length))
                .collect(Collectors.joining(", "));
        System.out.println("  iterSplit -> " + parts.size() + " payloads, sizes [" + sizes + "]");

        List<byte[]> segments = new ArrayList<>();
        for (byte[] segment : EdiFabricX12.iterMerge(parsedJson)) {
            segments.add(segment);
        }
        System.out.println("  iterMerge -> " + segments.size() + " segments");
    }

    /** clear_cache, shutdown_logger */
    private static void demoTeardown() {
        section("Teardown: clear_cache, shutdown_logger");

        EdiFabricX12.clearCache();
        System.out.println("  clear_cache -> map, license, stream state and results reset");
        EdiFabricX12.shutdownLogger();
        System.out.println("  shutdown_logger -> logger stopped");
    }

    /** Load map/map.json and point each entry at the local map folder. */
    private static JSONObject loadLocalMap() throws IOException {
        JSONObject modelMap = new JSONObject(readUtf8(MAP_PATH));
        String mapDir = MAP_PATH.getParent().toAbsolutePath().normalize().toString();
        JSONObject maps = modelMap.getJSONObject("maps");
        for (String key : maps.keySet()) {
            maps.getJSONObject(key).put("location", mapDir);
        }
        return modelMap;
    }

    /** Load map/map.json and set default to the serial for online resolution. */
    private static JSONObject loadOnlineMap(String serial) throws IOException {
        JSONObject modelMap = new JSONObject(readUtf8(MAP_PATH));
        modelMap.put("default", serial);
        return modelMap;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(title);
        System.out.println("======================================================================");
    }

    private static String preview(String text) {
        return preview(text, 400);
    }

    private static String preview(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + " ...";
    }

    private static Path findRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("map").resolve("map.json"))) {
            return cwd;
        }
        // Walk up from the class location (target/classes/...) toward the repo root.
        try {
            Path dir = Paths.get(ExampleAllFunctions.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            for (int i = 0; i < 8 && dir != null; i++) {
                if (Files.isRegularFile(dir.resolve("map").resolve("map.json"))) {
                    return dir;
                }
                dir = dir.getParent();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return cwd;
    }

    private static final class Options {
        final String serial;
        final String libraryPath;

        private Options(String serial, String libraryPath) {
            this.serial = serial;
            this.libraryPath = libraryPath;
        }

        static Options parse(String[] args) {
            String serial = null;
            String libraryPath = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--serial":
                        if (i + 1 >= args.length) {
                            printUsage();
                            return null;
                        }
                        serial = args[++i];
                        break;
                    case "--lib":
                        if (i + 1 >= args.length) {
                            printUsage();
                            return null;
                        }
                        libraryPath = args[++i];
                        break;
                    case "--help":
                    case "-h":
                        printUsage();
                        return null;
                    default:
                        System.err.println("Unrecognized argument: " + args[i]);
                        printUsage();
                        return null;
                }
            }

            if (serial == null) {
                String env = System.getenv("EDIFABRIC_SERIAL");
                serial = env == null || env.isEmpty() ? DEFAULT_SERIAL : env;
            }
            return new Options(serial, libraryPath);
        }

        private static void printUsage() {
            System.out.println("Usage: mvn -q exec:java -Dexec.args=\"[--serial SERIAL] [--lib PATH]\"");
            System.out.println();
            System.out.println("  --serial SERIAL   license serial (default: EDIFABRIC_SERIAL or the free plan serial)");
            System.out.println("  --lib PATH        edifabric-x12-tools library file or its folder");
        }
    }
}
