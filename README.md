# ediFabric Native X12 - Java bindings

**ediFabric Native** is a self-contained, high-performance X12 EDI native shared library. It converts X12 EDI to JSON (and back),
validates transaction sets, and generates acknowledgments — callable from **any
language with a C foreign-function interface** (C, C++, Rust, Go, Python, Node.js,
Java/JNA, .NET, …). No .NET runtime, JVM, or other dependency is required on the
target machine beyond the shared library itself.

Java [JNA](https://github.com/java-native-access/jna) bindings for [ediFabric Native](https://www.edifabric.com/edifabric-native.html).

| File | Purpose |
| --- | --- |
| `src/main/java/com/edifabric/nativex12/EdiFabricX12.java` | high-level API over the C ABI |
| `src/main/java/com/edifabric/nativex12/EdiFabricNative.java` | JNA interface mirroring the C header |
| `src/main/java/com/edifabric/nativex12/example/ExampleAllFunctions.java` | runnable walkthrough of every entry point |
| `c-abi-edifabric_x12_tools.h` | the C header these bindings mirror |

## Requirements

- Java 8 or later
- Apache Maven 3.6+
- 64-bit Windows, Linux, or macOS
- The native library for your platform:

| Platform | File |
| --- | --- |
| Windows | `edifabric-x12-tools.dll` |
| Linux | `edifabric-x12-tools.so` |
| macOS | `edifabric-x12-tools.dylib` |

1. [Sign up free for **Community**](https://www.edifabric.com/pricing.html) to get an evaluation serial key. Community never expires, requires no credit card, and is limited to 250 operations per day for non-production use. After signup, retrieve your serial from [Your Account](https://support.edifabric.com/hc/en-us/articles/360007159031-Your-Account-API-key).
2. [Download the **ediFabric Native** library](https://support.edifabric.com/hc/en-us/articles/37289848931869-Download).

Plus your **model files** (per transaction set) and a **map file** that tells the
engine where to find them. See [Model map](#model-map) for details.

## Getting started

**Sign up free for Community** at [edifabric.com/pricing](https://www.edifabric.com/pricing.html)
to get an evaluation serial key, then **download the library** from
[here](https://support.edifabric.com/hc/en-us/articles/37289848931869-Download).
Put the native library in the repository root, then run the walkthrough with your serial:

```bash
mvn -q exec:java -Dexec.args="--serial YOUR_SERIAL"
```

It authorizes with your Community (or paid) serial, loads the model map, and calls
every function in the ABI, printing what each one returns.

```
======================================================================
Parse: parse (mode 2, JSON + validation report)
======================================================================
  1754 bytes total, validation starts at offset 1708
  validation -> {"errors":[],"errors_count":0,"data_count":10}
```

Options:

```bash
mvn -q exec:java -Dexec.args="--serial YOUR_SERIAL"   # Community or paid serial (required)
mvn -q exec:java -Dexec.args="--lib /opt/edifabric"    # library file or folder
```

You can also set `EDIFABRIC_SERIAL` instead of passing `--serial`.

The library path is resolved from `--lib`, then `EDIFABRIC_X12_LIB`, then the
working directory and a few levels above the classpath root. The serial comes from
`--serial`, then `EDIFABRIC_SERIAL`.

All strings and payloads cross the boundary as **UTF‑8 byte buffers**
(`pointer + length`). Every function returns `0` on success or a non-zero
[error code](#error-codes).

## Usage

Add the sources (or built jar) and the JNA dependency to your project:

```xml
<dependency>
  <groupId>net.java.dev.jna</groupId>
  <artifactId>jna</artifactId>
  <version>5.17.0</version>
</dependency>
```

```java
import com.edifabric.nativex12.EdiFabricX12;
import com.edifabric.nativex12.ParseMode;
import com.edifabric.nativex12.ParseResult;

String serial = "your-serial";   // from your Community or paid plan

EdiFabricX12.loadLibrary();              // or loadLibrary("C:\\libs\\edifabric-x12-tools.dll")
EdiFabricX12.setSerial(serial);          // Community: setSerial. Developer: prefer ensureToken. Enterprise: prefer setToken.
EdiFabricX12.setMap("{\"default\":\"" + serial + "\",\"maps\":{}}");

byte[] edi = Files.readAllBytes(Path.of("837p.txt"));
ParseResult result = EdiFabricX12.parse(new String(edi, StandardCharsets.UTF_8), ParseMode.JSON);
System.out.println(result.getTransactions());
```

### Validation and acknowledgments

`parse` returns a `ParseResult`. In modes 2 and 3, `getOffset()` marks where the
validation and acknowledgment JSON starts inside the output. Use
`getTransactions()` and `getReport()` to split them.

```java
String config = """
    {
      "validate": { "snip_level": 2, "max_errors": 0 },
      "ack": { "gen997": false, "supress_ta1": false }
    }
    """;

ParseResult result = EdiFabricX12.parse(edi, ParseMode.JSON_VALIDATE_ACK, config);
JSONObject report = new JSONObject(result.getReport());
System.out.println(report.getInt("errors_count"));
```

| Mode | Constant | Output |
| --- | --- | --- |
| 1 | `ParseMode.JSON` | transaction-set JSON |
| 2 | `ParseMode.JSON_VALIDATE` | JSON plus a validation report |
| 3 | `ParseMode.JSON_VALIDATE_ACK` | JSON plus validation and a 999/997/TA1 acknowledgment |

### Streaming large interchanges

`iterSplit` streams one transaction set (or repeating loop) at a time with flat
memory use. `segment_id` must be `ST` or the first segment of a repeating loop.

```java
String config = "{\"split\":{\"segment_id\":\"LX\",\"segment_depth\":6,\"loop_id\":\"2400\"}}";

for (SplitPart part : EdiFabricX12.iterSplit(edi, ParseMode.JSON, config)) {
    handle(new String(part.getPayload(), StandardCharsets.UTF_8));
}
```

`iterMerge` streams a full interchange JSON document back out one segment at a time:

```java
try (OutputStream out = Files.newOutputStream(Path.of("out.edi"))) {
    for (byte[] segment : EdiFabricX12.iterMerge(transactions)) {
        out.write(segment);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
```

Both wrap the underlying `startSplit`/`split`/`getResult` and
`startMerge`/`merge`/`getResult` sequences, which you can also drive directly.

### Building EDI

```java
String ediText = EdiFabricX12.build(transactions, "\r\n");   // null postfix for compact output
```

## API reference

Conventions used by every function:

- Returns `int`: `0` = success, non-zero = [error code](#error-codes).
- Inputs are UTF‑8 `byte*` + `int length`.
- Output functions use **grow-and-retry**: if the buffer is too small the call
  returns `1` (`InsufficientCapacity`) and writes the required size into the
  length out-parameter; reallocate and call again.
- Exceptions never cross the boundary.

Every wrapper throws `EdiFabricException` when the native call returns a non-zero
status. Buffer growth (`InsufficientCapacity`) is retried automatically.

| Group | Methods |
| --- | --- |
| Loading | `loadLibrary`, `setLibraryPath`, `getResolvedLibraryPath` |
| Lifecycle | `initLogger`, `shutdownLogger`, `clearCache` |
| Licensing | `ensureToken`, `getAppVersion`, `getToken`, `validateToken`, `setToken`, `getTokenExpiration`, `getTokenExpirationTicks`, `setSerial` |
| Model map | `setMap` |
| Processing | `parse`, `startSplit`, `split`, `build`, `startMerge`, `merge`, `getResult` |
| Errors | `getError`, `freeError`, `check` |
| Wrappers | `iterSplit`, `iterMerge` |
| Types | `ParseMode`, `LogLevel`, `ErrorCode`, `EdiFabricException`, `ParseResult`, `SplitStep`, `SplitPart`, `EdiFabricX12.Raw` |

`getError` frees the native string for you. Call `freeError` only if you invoke
`EdiFabricX12.Raw.getError` yourself. Builds that do not export `free_error` fall
back to JNA's `Native.free`.

## Licensing

> [!NOTE]
> Sign up free for the [Community plan](https://www.edifabric.com/pricing.html)
> to get an evaluation serial key. Community never expires, requires no credit
> card, and is for non-production evaluation, learning, and prototyping
> (250 operations per day). After signup, copy your serial from
> [Your Account](https://support.edifabric.com/hc/en-us/articles/360007159031-Your-Account-API-key).
>
> If you hit the Community daily quota, native calls return [error 639](#error-codes);
> upgrade at [edifabric.com/pricing](https://www.edifabric.com/pricing.html) if you
> want to continue.

| Plan | What works | Recommended |
| --- | --- | --- |
| Community | `setSerial` only | `setSerial` |
| Developer | `setSerial` and `ensureToken` (`ensureToken` caches the result for 1 day) | `ensureToken` |
| Enterprise | `setSerial`, `ensureToken`, `getToken` / `setToken` | `setToken` (offline tokens) |

```java
// Community: authorize per process against the license server
EdiFabricX12.setSerial(serial);

// Developer (recommended): 1-day built-in cache; refreshes if the token expires within N seconds
EdiFabricX12.ensureToken(serial, 3600);
System.out.println(EdiFabricX12.getTokenExpiration());   // Instant, or null when unset

// Developer (also works): same as Community, online check per process
EdiFabricX12.setSerial(serial);

// Enterprise (recommended): fetch / validate / set an offline token yourself
String token = EdiFabricX12.getToken(serial);
EdiFabricX12.validateToken(token);
EdiFabricX12.setToken(token);
```

## Model map

`setMap` tells the engine where to find transaction-set models. Keys are
`message:version`. Set `default` to your serial to resolve unmapped transaction
sets through the online spec service, or leave it `null` (or `""`) and map
everything locally.

The example builds that JSON at runtime instead of hard-coding paths. Online
fallback is a `JSONObject` with `default` set to your serial:

```java
JSONObject map = new JSONObject();
map.put("default", serial);
map.put("maps", new JSONObject());

EdiFabricX12.setMap(map.toString());
```

For local models, load a map file and rewrite each entry's `location` to the
folder that actually holds the JSON files (see `demoSetLocalMap` in
`ExampleAllFunctions.java`):

```java
Path mapLocation = Path.of("map").toAbsolutePath().normalize();
JSONObject localMap = new JSONObject(Files.readString(mapLocation.resolve("map.json")));
JSONObject maps = localMap.getJSONObject("maps");
for (String key : maps.keySet()) {
    maps.getJSONObject(key).put("location", mapLocation.toString());
}

EdiFabricX12.setMap(localMap.toString());
```

`map.json` lists each transaction set; `location` is filled in at runtime so the
same file works from any working directory:

```json
{
  "default": "",
  "maps": {
    "837:005010X222A1": { "type": 1, "name": "model837P.json", "location": "" },
    "834:005010X220A1": { "type": 1, "name": "model834.json",  "location": "" }
  }
}
```

You can also mix both: keep `default` as your serial and add local entries under
`maps` for the transaction sets you ship on disk.

All X12 transactions, such as 837P, 834, 850, etc. are represented as proprietary JSON.
Download a standard model from [EdiNation Spec Library](https://edination.edifabric.com/edi-spec-library.html),
or a custom model from [EdiNation Spec Builder](https://edination.edifabric.com/edi-spec-builder.html).
Create/modify models in OpenEDI format, upload them in EdiNation Spec Builder and download them as JSON for use in ediFabric Native.

To download a model in either EdiNation Spec Library or EdiNation Spec Builder,
select the model first, then in the JSON view
select the Download button in the top right corner.

![Model Img](https://github.com/EdiFabric/edifabric-java-bindings/blob/main/model.png)

Choose to download as **ediFabric Native**.

## Configuration JSON

All JSON uses **`snake_case`** keys and is case-insensitive.

### ParseConfig (`parse`)

```json
{
  "validate": { "regex": null, "date_format": null, "time_format": null,
                "skip_seq_count": false, "skip_hl_seq": false,
                "snip_level": 0, "max_errors": 0 },
  "ack":      { "supress_ta1": false, "ak901p": false,
                "gen_for_valid": false, "gen997": false }
}
```

- `validate` — applied when `mode ≥ 2`; `snip_level` is `1`–`4`.
- `ack` — applied when `mode == 3`.

All sections are optional for `parse`.

### SplitConfig (`start_split`)

```json
{
  "split":    { "segment_id": "ST", "segment_depth": 0, "loop_id": null }
}
```

- `split` — required for `start_split`.

Splitting is possible for the following boundaries:

- Transaction - for files that contain batches of transactions.
- Repeating loop - for files that contain batches of loops, such as order lines, claims or benefit enrollments.

The splitter must be configured as follows:

- `segment_id`  — the name of the segment to split by. It must be either ST or the first segment in the repeatable loop (Mandatory).
- `segment_depth` — the depth of the segment in the model hierarchy (Mandatory).
- `loop_id` — the name of the loop for the segment specified in segment_id (Optional).

The values for the splitter can be found in EdiNation by loading a sample file. For example, if you want to split by loop 2000A in 837P, load an 837P file in EdiNation (or use the example one), click on the first segment in that loop, e.g., HL. `segment_id` is **CODE**,  `loop_id` is the last item in **PATH**, and `segment_depth` is **DEPTH**.

> [!NOTE]
> If a segment does not show a SPLITTER copy button, than splitting is not possible by that segment.

The easiest way to get the splitter configuration is to click on the copy button under SPLITTER that has the full splitter JSON pre-configured.

![Model Img](https://github.com/EdiFabric/edifabric-java-bindings/blob/main/splitter.png)

## Threading

The library holds process-global state (model map, active split reader, active
merge writer, last result, license) behind an internal lock. `parse` and `build`
are independent per call, but each split or merge sequence must run to
completion without another split or merge interleaving from a different thread.

## Error codes

`0` is success and `1` means the output buffer was too small. Library-level codes
are exposed as `ErrorCode`, and `getError(code)` returns the message.
Validation codes (elements, segments, transaction sets, groups, and interchanges)
appear in the parse report when `mode ≥ 2`.

**Error 639** means the Community (evaluation) daily quota was exceeded.
Upgrade your plan at [edifabric.com/pricing](https://www.edifabric.com/pricing.html)
if you wish to continue.

### Parser and library

| Code | Meaning |
| --- | --- |
| 1 | The suggested output buffer size is too small |
| 501 | Unexpected error. Contact support@edifabric.com and include a sample project/file to reproduce the issue |
| 502 | No connection to EdiNation API |
| 503 | The model map configuration is invalid. Check the paths and the model file names are correct |
| 611 | The input buffer is either null or its size is nill |
| 612 | The logger failed to log |
| 613 | The map configuration file is invalid |
| 614 | The output capacity must be positive |
| 615 | Models map must be set before parsing or splitting |
| 616 | Mode must be any of: 1 - Parse, 2 - Parse and Validate, 3 - Parse and Validate and Acknowledge |
| 617 | Parser failed. Contact support@edifabric.com and include a sample project/file to reproduce the issue |
| 618 | Validation failed. Contact support@edifabric.com and include a sample project/file to reproduce the issue |
| 619 | Validation serializer failed. Contact support@edifabric.com and include a sample project/file to reproduce the issue |
| 620 | The token is invalid. Contact support@edifabric.com for assistance |
| 621 | The configuration file is invalid |
| 622 | The split segment ID must not be blank |
| 623 | Call `startSplit` before splitting |
| 624 | The result can't be retrieved. Contact support@edifabric.com and include a sample project/file to reproduce the issue |
| 625 | Result buffer size mismatched |
| 626 | Call `startMerge` before merging |
| 627 | The output buffer is either null or its size is nill |
| 628 | The serial number is missing or incorrect. `getToken` doesn't work with Developer license |
| 629 | License was not installed. Contact support@edifabric.com for assistance |
| 630 | No license to use this version. Contact support@edifabric.com for assistance |
| 631 | The token has expired. Get and set a new token to continue |
| 632 | The token is missing. Set token to continue |
| 633 | Reached the maximum number of licenses. Set token to continue |
| 634 | Environment not recognized for licensing or reached the maximum number of licenses |
| 635 | Serial or token not found. Either set token or serial to continue |
| 636 | The rate to get serials was exceeded for your license. Wait for 60 seconds and try again or upgrade your license |
| 637 | Invalid JSON. Enable logging for additional details |
| 638 | The operation is not supported by your license |
| 639 | Community daily quota exceeded. Your license has reached its daily call limit. Upgrade your plan at edifabric.com to continue |

## Troubleshooting

**`UnsatisfiedLinkError: Could not load edifabric-x12-tools.dll`** — the library is
not on any searched path. Pass `EdiFabricX12.loadLibrary("/path/to/library")` or set
`EDIFABRIC_X12_LIB`.

**Error 615 on parse** — call `setMap` before parsing or splitting. `clearCache`
resets the map, so reload it afterwards.

**Error 628 / 635 on parse** — authorize first: `setSerial` on Community,
`ensureToken` (or `setSerial`) on Developer, or `setToken` on Enterprise.

**Error 633 on ensure_token / get_token** — the plan's machine quota is used up.
Contact support.

**Error 639** — the Community (evaluation) daily quota was exceeded. Wait until
the next day, or [upgrade your plan](https://www.edifabric.com/pricing.html) if
you wish to continue.

## Links

- [Documentation](https://support.edifabric.com/hc/en-us/articles/37276016388125-Introduction)
- [Product page](https://www.edifabric.com/edifabric-native.html)
- [Community plan (free signup)](https://www.edifabric.com/pricing.html)
- [Your Account](https://support.edifabric.com/hc/en-us/articles/360007159031-Your-Account-API-key)
- Support: support@edifabric.com
