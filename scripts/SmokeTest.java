import com.edifabric.nativex12.EdiFabricX12;

/**
 * Checks that the release jar loads the library it bundles. Run it from a folder with no
 * copy of the library, with the jar and JNA on the classpath:
 * {@code java -cp <jar><sep><jna.jar> scripts/SmokeTest.java}
 */
public class SmokeTest {
    public static void main(String[] args) {
        EdiFabricX12.loadLibrary();
        String loaded = EdiFabricX12.getResolvedLibraryPath();
        System.out.println("loaded " + loaded);
        if (!loaded.contains("edifabric-x12")) {
            throw new IllegalStateException("Expected the library extracted from the jar, got " + loaded);
        }
        System.out.println("getAppVersion -> " + EdiFabricX12.getAppVersion());

        // The license check uses OpenSSL on Linux, which loading alone doesn't.
        String serial = System.getenv("EDIFABRIC_SERIAL");
        EdiFabricX12.ensureToken(serial == null || serial.isEmpty() ? "bd96a836feca45cb91c86ee65d281f52" : serial, 3600);
        System.out.println("ensureToken -> ok");
    }
}
