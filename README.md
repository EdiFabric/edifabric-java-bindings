# ediFabric Native X12 — Java bindings

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

[Download **ediFabric Native** Library](https://support.edifabric.com/hc/en-us/articles/37289848931869-Download)

Plus your **model files** (per transaction set) and a **map file** that tells the
engine where to find them. See [Model map](#model-map) for details.

## Getting started

**Download the library** from [here](https://support.edifabric.com/hc/en-us/articles/37289848931869-Download).
Put the native library in the repository root, then run the walkthrough:

```bash
mvn -q exec:java
```

It authorizes with the free plan serial, loads the model map, and calls every
function in the ABI, printing what each one returns.

```
Parse: parse (mode 2, JSON + validation report)
  1754 bytes total, validation starts at offset 1708
  validation -> {"errors":[],"errors_count":0,"data_count":10}
```

Options:

```bash
mvn -q exec:java -Dexec.args="--serial YOUR_SERIAL"   # use your own license
mvn -q exec:java -Dexec.args="--lib /opt/edifabric"    # library file or folder
```

The library path is resolved from `--lib`, then `EDIFABRIC_X12_LIB`, then the
working directory and a few levels above the classpath root. The serial comes from
`--serial`, then `EDIFABRIC_SERIAL`, then the built-in free plan serial.

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

String serial = "your-serial";

EdiFabricX12.loadLibrary();              // or loadLibrary("C:\\libs\\edifabric-x12-tools.dll")
EdiFabricX12.setSerial(serial);          // or setToken(token) for offline use (Enterprise only)
EdiFabricX12.setMap("{\"default\":\"" + serial + "\",\"maps\":{}}");

String edi = Files.readString(Path.of("purchase-order.edi"));
ParseResult result = EdiFabricX12.parse(edi, ParseMode.JSON);
System.out.println(result.getTransactions());
```

Read a file from a subfolder of the project:

```java
Path edi = Path.of("edi", "837p.txt");
String text = Files.readString(edi);
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
> The examples are available with a free plan which can be used only with Serial model validation.
> You don't need to call `ensureToken` with the free plan, and the only licensing call must be `setSerial`.

The serial key for the free plan is:
```
bd96a836feca45cb91c86ee65d281f52
```

Two models are supported. Tokens are recommended for containers, air-gapped
machines, and high volume; serials are simplest when always online.

```java
// Token: refresh when expiry is within N seconds, or fetch/set explicitly
EdiFabricX12.ensureToken(serial, 3600);   // refresh if expiring within 1 hour
// or:
String token = EdiFabricX12.getToken(serial);
EdiFabricX12.setToken(token);
System.out.println(EdiFabricX12.getTokenExpiration());   // Instant, or null when unset

// Serial: authorize per process against the license server
EdiFabricX12.setSerial(serial);
```

## Model map

`setMap` tells the engine where to find transaction-set models. Keys are
`message:version`. Set `default` to your serial to resolve unmapped transaction
sets through the online spec service, or leave it empty/`null` and map everything locally.

```json
{
  "default": null,
  "maps": {
    "837:005010X222A1": { "type": 1, "name": "837P.json", "location": "/opt/models" },
    "850:005010":       { "type": 1, "name": "850.json",  "location": "/opt/models" }
  }
}
```

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

| Code | Meaning |
| --- | --- |
| 501 | Unknown |
| 502 | No internet access to the authentication API |
| 503 | Local map file has invalid paths or file names |
| 611 | Incorrect or empty input |
| 612 | Logger initialization failed |
| 613 | Map JSON could not be deserialized |
| 614 | Negative output capacity |
| 615 | Model map not set, call `setMap` first |
| 616 | Mode must be 1, 2, or 3 |
| 617 | No JSON produced |
| 618 | Validation result unavailable |
| 619 | Validation report serialization failed |
| 620 | Incorrect token |
| 621 | Config JSON could not be deserialized |
| 622 | Split `segment_id` missing or empty |
| 623 | `split` called before `startSplit` |
| 624 | No result available for `getResult` |
| 625 | `getResult` buffer size mismatch |
| 626 | `merge` called before `startMerge` |
| 627 | Incorrect or null output pointer |
| 628 | Incorrect serial |
| 629 | License not installed |
| 630 | Application maximum version exceeded |
| 631 | Token expired |
| 632 | Token missing |
| 633 | Maximum licenses exceeded |
| 634 | License snapshot not found |
| 635 | License not set, call `setToken`, `ensureToken`, or `setSerial` |
| 636 | Rate exceeded |
| 637 | Invalid JSON |
| 638 | Incorrect license |

## Troubleshooting

**`UnsatisfiedLinkError: Could not load edifabric-x12-tools.dll`** — the library is
not on any searched path. Pass `EdiFabricX12.loadLibrary("/path/to/library")` or set
`EDIFABRIC_X12_LIB`.

**Error 615 on parse** — call `setMap` before parsing or splitting. `clearCache`
resets the map, so reload it afterwards.

**Error 635 on parse** — authorize first with `setToken`, `ensureToken`, or `setSerial`.

**Error 633** — the plan's machine quota is used up. Switch to token authorization
or contact support.

## Links

- [Documentation](https://support.edifabric.com/hc/en-us/articles/37276016388125-Introduction)
- [Product page](https://www.edifabric.com/edifabric-native.html)
- Support: support@edifabric.com
