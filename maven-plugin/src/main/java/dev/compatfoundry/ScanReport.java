package dev.compatfoundry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The version-1 Maven report contract, including local repair-request text. */
public final class ScanReport {
    private ScanReport() { }

    public static ObjectNode payload(JsonNode scan) {
        ObjectNode payload = JvmScanner.JSON.createObjectNode().put("schema_version", "1").put("status", "ok");
        payload.putObject("summary").put("hit_count", scan.path("findings").size()).put("unknown_count", scan.path("unknown").size());
        payload.putObject("repair").put("request_file", "repair-request.md").put("contact", "unknown")
            .put("offering", "paid compatibility patch/PR pilot");
        payload.set("scan", scan);
        return payload;
    }

    public static String renderText(JsonNode report) {
        List<String> lines = new ArrayList<>(List.of(
            "Compat Foundry " + report.path("catalog_version").asText() + " | Boot " + report.path("from_version").asText() + " -> " + report.path("to_version").asText(),
            "Potential risks: " + report.path("findings").size() + " | unknown: " + report.path("unknown").size() + " | mitigation hints: " + report.path("mitigation_hints").size(),
            "Static signatures only; runtime impact must be confirmed."));
        for (JsonNode item : report.path("findings")) {
            lines.add("");
            lines.add("[" + item.path("severity").asText().toUpperCase(Locale.ROOT) + "] " + item.path("id").asText() + ": " + item.path("title").asText());
            lines.add("  Old: " + item.path("old_behavior").asText());
            lines.add("  New: " + item.path("new_behavior").asText());
            int count = 0;
            for (JsonNode pointer : item.path("evidence")) {
                if (count++ == 4) break;
                lines.add("  Evidence: " + pointer.path("file").asText() + ":" + pointer.path("line").asInt() + " (" + pointer.path("signal").asText() + ")");
            }
            lines.add("  Fix:");
            for (String line : item.path("fix").path("code").asText().split("\\R")) lines.add("    " + line);
            lines.add("  Apply: " + item.path("fix").path("application").asText());
            lines.add("  Review: " + item.path("fix").path("caveat").asText());
            for (JsonNode source : item.path("sources")) lines.add("  L3: " + source.path("url").asText());
        }
        for (String bucket : List.of("unknown", "mitigation_hints")) {
            if (report.path(bucket).isEmpty()) continue;
            List<String> ids = new ArrayList<>();
            report.path(bucket).forEach(item -> ids.add(item.path("id").asText()));
            lines.add("");
            lines.add((bucket.equals("unknown") ? "Unknown: " : "Mitigation hints (verify active scope): ") + String.join(", ", ids));
        }
        lines.add(""); lines.add("Coverage: " + report.path("coverage").asText());
        for (String bucket : List.of("warnings", "limitations")) report.path(bucket).forEach(note -> lines.add("Note: " + note.asText()));
        return String.join("\n", lines) + "\n";
    }

    public static String repairRequest(JsonNode report) {
        StringBuilder request = new StringBuilder("# Compatibility patch / PR pilot request\n\n"
            + "Optional paid pilot; no request is sent by this tool. Contact: unknown (not published).\n"
            + "Review and redact reports before sharing with a maintainer you choose.\n\n"
            + "Requested scope: reproduce selected behavior differences and deliver a reviewable compatibility patch/PR.\n"
            + "Acceptance: baseline / upgraded / patched runtime evidence; scope and price agreed first.\n\nSelected drift IDs:\n");
        for (JsonNode item : report.path("findings")) request.append("- ").append(item.path("id").asText()).append('\n');
        return request + "\nRepository/contact: [fill locally]\nExpected behavior: [fill locally]\n";
    }

    public static String write(Path output, JsonNode scan) throws IOException {
        Files.createDirectories(output);
        String text = renderText(scan) + "\nRepair entry: repair-request.md (paid compatibility patch/PR pilot; contact unknown).\n";
        Files.writeString(output.resolve("report.json"), JvmScanner.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload(scan)) + "\n");
        Files.writeString(output.resolve("report.txt"), text);
        Files.writeString(output.resolve("repair-request.md"), repairRequest(scan));
        return text;
    }
}
