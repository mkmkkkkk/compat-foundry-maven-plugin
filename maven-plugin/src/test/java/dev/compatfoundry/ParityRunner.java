package dev.compatfoundry;

import java.nio.file.Path;

/** Test-only process boundary against the final shaded artifact, never packaged. */
public final class ParityRunner {
    public static void main(String[] args) throws Exception {
        Object report = JvmScanner.class.getMethod("scan", Path.class, Path.class, String.class)
            .invoke(new JvmScanner(60), Path.of(args[0]), args[1].equals("-") ? null : Path.of(args[1]), args[2]);
        // Reflection keeps this test runner independent of the relocated JSON types.
        for (var method : ScanReport.class.getMethods()) {
            if (method.getName().equals("write")) method.invoke(null, Path.of(args[3]), report);
            if (method.getName().equals("payload")) System.out.println(method.invoke(null, report));
        }
    }
}
