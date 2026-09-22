package dev.compatfoundry;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JvmScannerTest {
    private final Path sample = Path.of("../scanner/examples/boot27").toAbsolutePath().normalize();

    @Test void sampleAndCatalogContract() throws Exception {
        assertEquals(12, JvmScanner.loadCatalog().path("drifts").size());
        var report = new JvmScanner(60).scan(sample.resolve("pom.xml"), sample.resolve("src/main"), "3.0.13");
        assertEquals(7, report.path("findings").size());
        assertEquals(0, report.path("unknown").size());
        assertEquals("1", ScanReport.payload(report).path("schema_version").asText());
        assertTrue(ScanReport.renderText(report).contains("Potential risks: 7 | unknown: 0"));
    }

    @Test void unknownEvidenceAndVersionStayUnknown() throws Exception {
        var report = new JvmScanner(60).scan(sample.resolve("pom.xml"), null, "3.0.13");
        assertTrue(report.path("unknown").size() > 0);
        var outside = new JvmScanner(60).scan(sample.resolve("pom.xml"), sample.resolve("src/main"), "3.5.0");
        assertEquals(12, outside.path("unknown").size());
        assertEquals(0, outside.path("findings").size());
        assertEquals("unknown", outside.path("coverage").asText());
    }

    @Test void invalidXmlAndSymlinkAreRejected() throws Exception {
        Path work = Path.of("target/jvm-tests");
        Files.createDirectories(work);
        Path dtd = work.resolve("dtd.xml");
        Files.writeString(dtd, "<!DOCTYPE project [<!ENTITY x 'payload'>]><project/>");
        assertTrue(assertThrows(Exception.class, () -> new JvmScanner(60).scan(dtd, null, "3.0.13")).getMessage().contains("DTD"));
        Path link = work.resolve("linked.xml");
        if (!Files.isSymbolicLink(link)) Files.createSymbolicLink(link, sample.resolve("pom.xml"));
        assertTrue(assertThrows(Exception.class, () -> new JvmScanner(60).scan(link, null, "3.0.13")).getMessage().contains("symlink"));
        assertThrows(IllegalArgumentException.class, () -> new JvmScanner(0));
    }
}
