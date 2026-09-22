package dev.compatfoundry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** JVM port of scanner.py. The bundled catalog remains the source of all rules. */
public final class JvmScanner {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 1_000_000;
    private static final int MAX_FILES = 5000;
    private static final Set<String> IGNORED = Set.of(".git", ".venv", "target", "build", "node_modules", "__pycache__", "test", "tests");
    private static final Set<String> EXTENSIONS = Set.of("java", "kt", "xml", "properties", "yml", "yaml", "factories", "imports");
    private static final Map<String, Integer> RANKS = Map.of("critical", 0, "high", 1, "medium", 2, "low", 3);
    private final long started = System.nanoTime();
    private final long timeoutNanos;

    public JvmScanner(int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("compat.timeoutSeconds must be positive");
        timeoutNanos = timeoutSeconds * 1_000_000_000L;
    }

    private void checkTime() throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - started > timeoutNanos)
            throw new IOException("Scanner timed out or interrupted");
    }

    static String readText(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("symlink input is unsupported: " + path);
        if (Files.size(path) > MAX_BYTES) throw new IOException("input exceeds " + MAX_BYTES + " bytes: " + path);
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static JsonNode resource(String name) throws IOException {
        try (InputStream input = JvmScanner.class.getResourceAsStream("/compat-runtime/catalog/" + name)) {
            if (input == null) throw new IOException("Missing bundled catalog: " + name);
            return JSON.readTree(input);
        }
    }

    static JsonNode loadCatalog() throws IOException {
        JsonNode catalog = resource("drifts.json");
        JsonNode schema = resource("schema.json");
        validateSchema(catalog, schema, schema);
        Set<String> ids = new HashSet<>();
        for (JsonNode item : catalog.path("drifts")) {
            if (!ids.add(item.path("id").asText())) throw new IOException("catalog IDs must be unique");
            for (JsonNode source : item.path("sources")) {
                URI uri = URI.create(source.path("url").asText());
                String host = uri.getHost();
                boolean allowed = Set.of("docs.spring.io", "docs.hibernate.org", "docs.jboss.org").contains(host == null ? "" : host)
                    || "github.com".equals(host) && uri.getPath().startsWith("/spring-projects/");
                if (!"https".equals(uri.getScheme()) || !allowed)
                    throw new IOException("catalog source must be an official Spring or Hibernate URL");
            }
            validateRule(item.path("detection").path("when"));
            validateRule(item.path("detection").path("mitigated_by"));
        }
        return catalog;
    }

    /** Validate the keywords used by the immutable bundled schema, without network resolution. */
    private static void validateSchema(JsonNode value, JsonNode schema, JsonNode root) throws IOException {
        if (schema.has("$ref")) { validateSchema(value, root.at(schema.get("$ref").asText().substring(1)), root); return; }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode alternative : schema.get("oneOf")) {
                try { validateSchema(value, alternative, root); matches++; } catch (IOException ignored) { }
            }
            if (matches != 1) throw new IOException("catalog schema: expected one matching rule");
        }
        if (schema.has("const") && !schema.get("const").equals(value)) throw new IOException("catalog schema: invalid constant");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) found |= option.equals(value);
            if (!found) throw new IOException("catalog schema: invalid enum");
        }
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean(); default -> true;
        };
        if (!valid) throw new IOException("catalog schema: expected " + type);
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.has(required.asText())) throw new IOException("catalog schema: missing " + required.asText());
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode child = schema.path("properties").get(field.getKey());
                if (child != null) validateSchema(field.getValue(), child, root);
                else if (schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean())
                    throw new IOException("catalog schema: unexpected " + field.getKey());
            }
        }
        if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt()) throw new IOException("catalog schema: array too short");
            if (schema.has("items")) for (JsonNode child : value) validateSchema(child, schema.get("items"), root);
        }
        if (value.isTextual() && (value.asText().length() < schema.path("minLength").asInt()
                || schema.has("pattern") && !regex(schema.get("pattern").asText()).matcher(value.asText()).find()))
            throw new IOException("catalog schema: invalid string");
    }

    private static void validateRule(JsonNode rule) {
        var field = rule.fields().next();
        JsonNode value = field.getValue();
        switch (field.getKey()) {
            case "all", "any" -> { for (JsonNode child : value) validateRule(child); }
            case "not" -> validateRule(value);
            case "source_regex" -> regex(value.asText());
            case "property" -> { regex(value.path("key").asText()); regex(value.path("value").asText()); }
            case "file_regex" -> regex(value.path("pattern").asText());
            default -> { }
        }
    }

    private static Pattern regex(String pattern) { return Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS); }
    private static String resolve(String value, java.util.Properties properties) {
        for (int i = 0; i < 20; i++) {
            Matcher matcher = regex("\\$\\{([^}]+)\\}").matcher(value);
            String updated = matcher.replaceAll(m -> Matcher.quoteReplacement(properties.getProperty(m.group(1), m.group())));
            if (updated.equals(value)) return value;
            value = updated;
        }
        return value;
    }
    private static String or(String value, String fallback) { return value == null ? fallback : value; }
    private record Dep(String group, String artifact, String version, String scope) { }
    private record Pom(String boot, List<Dep> dependencies, List<String> warnings, String file) { }

    private Pom readPom(Path path) throws Exception {
        String text = readText(path);
        if (Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE).matcher(text).find())
            throw new IOException("XML DTD and entity declarations are unsupported");
        // Maven's model intentionally does not distinguish absent and empty containers.
        // Retain those presence bits for exact Python warning semantics.
        Set<String> elements = new HashSet<>();
        List<Boolean> exclusions = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader xml = factory.createXMLStreamReader(new StringReader(text));
        List<String> stack = new ArrayList<>();
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    stack.add(xml.getLocalName());
                    String location = String.join("/", stack);
                    elements.add(location);
                    if (location.equals("project/dependencies/dependency")) exclusions.add(false);
                    if (location.equals("project/dependencies/dependency/exclusions")) exclusions.set(exclusions.size() - 1, true);
                } else if (event == XMLStreamConstants.END_ELEMENT) stack.remove(stack.size() - 1);
            }
        } finally { xml.close(); }
        if (!elements.contains("project")) throw new IOException("Maven input must have a project root");
        Model model = new MavenXpp3Reader().read(new StringReader(text), false);
        var properties = model.getProperties();
        List<Dep> dependencies = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int dependencyIndex = 0;
        for (Dependency dependency : model.getDependencies()) {
            boolean hasExclusions = exclusions.get(dependencyIndex++);
            String scope = resolve(or(dependency.getScope(), "compile"), properties);
            if (Set.of("test", "import").contains(scope)) continue;
            dependencies.add(new Dep(resolve(or(dependency.getGroupId(), ""), properties),
                resolve(or(dependency.getArtifactId(), ""), properties),
                resolve(or(dependency.getVersion(), "managed"), properties), scope));
            if (hasExclusions)
                warnings.add("Dependency exclusions exist: inferred starter capabilities need manual review.");
        }
        Set<String> versions = new HashSet<>();
        Parent parent = model.getParent();
        if (parent != null) bootCandidate(versions, parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), properties);
        if (model.getDependencyManagement() != null)
            for (Dependency d : model.getDependencyManagement().getDependencies())
                bootCandidate(versions, d.getGroupId(), d.getArtifactId(), d.getVersion(), properties);
        if (versions.isEmpty()) for (Dep d : dependencies)
            if (d.group.equals("org.springframework.boot") && d.artifact.startsWith("spring-boot") && !d.version.equals("managed"))
                versions.add(d.version);
        String boot = versions.size() == 1 ? versions.iterator().next() : "unknown";
        if (version(boot) == null) boot = "unknown";
        if (elements.contains("project/profiles")) warnings.add("Maven profiles are not evaluated; use an effective POM for selected profiles.");
        if (elements.contains("project/modules")) warnings.add("Multi-module root detected: scan each module using an effective POM.");
        if (parent != null && !"org.springframework.boot".equals(parent.getGroupId()))
            warnings.add("External parent dependency inheritance is unknown; supply an effective POM.");
        if (dependencies.stream().anyMatch(d -> (d.group + d.artifact + d.version).contains("${")))
            warnings.add("Unresolved Maven properties exist; inherited dependencies are unknown.");
        return new Pom(boot, dependencies, warnings, path.toString());
    }

    private static void bootCandidate(Set<String> versions, String group, String artifact, String version, java.util.Properties properties) {
        if (resolve(or(group, ""), properties).equals("org.springframework.boot")
                && Set.of("spring-boot-starter-parent", "spring-boot-dependencies").contains(or(artifact, "")))
            versions.add(resolve(or(version, ""), properties));
    }

    private static int comparePaths(Path left, Path right) {
        for (int i = 0; i < Math.min(left.getNameCount(), right.getNameCount()); i++) {
            int result = left.getName(i).toString().compareTo(right.getName(i).toString());
            if (result != 0) return result;
        }
        return Integer.compare(left.getNameCount(), right.getNameCount());
    }

    private record SourceFile(String file, String name, String text, boolean code) { }
    private record Property(String value, String file, int line, boolean conditional) { }
    private static final class Inventory {
        boolean provided;
        boolean complete;
        final List<SourceFile> files = new ArrayList<>();
        final Map<String, List<Property>> properties = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
    }

    static String stripComments(String text) {
        Matcher matcher = regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|/\\*[\\s\\S]*?\\*/").matcher(text);
        return matcher.replaceAll(m -> Matcher.quoteReplacement(m.group().startsWith("//") || m.group().startsWith("/*")
            ? m.group().replaceAll("[^\\n]", " ") : m.group()));
    }
    private static Map<String, String> readProperties(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String row : text.replaceAll("\\\\\\r?\\n\\s*", "").split("\\R")) {
            String line = row.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            Matcher matcher = regex("([^\\s=:]+)\\s*(?:[=:]\\s*|\\s+)?(.*)$").matcher(line);
            if (matcher.matches()) result.put(matcher.group(1), matcher.group(2).strip());
        }
        return result;
    }
    private static void flattenYaml(Object value, String prefix, int depth, Map<String, String> result) {
        if (depth > 30) throw new IllegalArgumentException("YAML nesting or alias recursion exceeds 30 levels");
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) flattenYaml(entry.getValue(), prefix.isEmpty() ? pythonString(entry.getKey())
                : prefix + "." + pythonString(entry.getKey()), depth + 1, result);
        } else if (value instanceof List<?> list) {
            result.put(prefix, String.join(",", list.stream().map(JvmScanner::pythonString).toList()));
        } else if (value != null) result.put(prefix, value instanceof Boolean ? value.toString() : pythonString(value));
    }
    private static String pythonString(Object value) {
        if (value == null) return "None";
        if (value instanceof Boolean flag) return flag ? "True" : "False";
        return value.toString();
    }
    private Inventory inventory(Path source) throws Exception {
        Inventory result = new Inventory();
        result.provided = result.complete = source != null;
        if (source == null) return result;
        if (!Files.isDirectory(source) || Files.isSymbolicLink(source)) throw new IOException("source must be a real directory: " + source);
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(source)) { paths = walk.filter(p -> !p.equals(source)).sorted(JvmScanner::comparePaths).toList(); }
        int count = 0;
        for (Path path : paths) {
            checkTime();
            Path relative = source.relativize(path);
            boolean ignored = false;
            for (Path part : relative) ignored |= IGNORED.contains(part.toString());
            if (ignored) continue;
            boolean symlink = false;
            for (Path parent = path; parent != null; parent = parent.getParent())
                if (!parent.equals(source.getParent()) && Files.isSymbolicLink(parent)) symlink = true;
            if (symlink) { result.complete = false; result.warnings.add("Symlink skipped: " + relative); continue; }
            String name = path.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1);
            if (!Files.isRegularFile(path) || !EXTENSIONS.contains(extension)) continue;
            if (++count > MAX_FILES) { result.complete = false; result.warnings.add("Source file limit reached; remaining source is unknown."); break; }
            String text;
            try { text = readText(path); }
            catch (IOException error) { result.complete = false; result.warnings.add(error.getMessage()); continue; }
            boolean code = Set.of("java", "kt").contains(extension);
            result.files.add(new SourceFile(path.toString(), relative.toString(), code ? stripComments(text) : text, code));
            if (!name.startsWith("application") || !Set.of("properties", "yaml", "yml").contains(extension)) continue;
            boolean profile = !name.substring(0, name.lastIndexOf('.')).equals("application");
            if (profile) result.warnings.add("Profile configuration retained as conditional evidence: " + relative);
            try {
                List<Map<String, String>> documents = new ArrayList<>();
                if (extension.equals("properties")) documents.add(readProperties(text));
                else {
                    LoaderOptions options = new LoaderOptions();
                    options.setMaxAliasesForCollections(Integer.MAX_VALUE);
                    options.setNestingDepthLimit(1000);
                    for (Object document : new Yaml(new SafeConstructor(options)).loadAll(text)) {
                        if (document == null) continue;
                        Map<String, String> flat = new LinkedHashMap<>();
                        flattenYaml(document, "", 0, flat); documents.add(flat);
                    }
                }
                for (var document : documents) {
                    boolean conditional = profile || document.containsKey("spring.config.activate.on-profile") || document.containsKey("spring.profiles");
                    if (document.containsKey("spring.config.import") || document.containsKey("spring.config.location") || document.containsKey("spring.config.additional-location"))
                        result.warnings.add("External config locations/imports are not loaded; deployed overrides are unknown.");
                    for (var entry : document.entrySet()) {
                        String key = entry.getKey();
                        String[] lines = text.split("\\n", -1);
                        Pattern keyPattern = regex("\\s*" + Pattern.quote(key.substring(key.lastIndexOf('.') + 1)) + "\\s*:");
                        int line = 1;
                        for (int i = 0; i < lines.length; i++) if (lines[i].contains(key) || keyPattern.matcher(lines[i]).lookingAt()) { line = i + 1; break; }
                        result.properties.computeIfAbsent(key, unused -> new ArrayList<>()).add(new Property(entry.getValue(), path.toString(), line, conditional));
                    }
                }
            } catch (RuntimeException error) {
                result.complete = false;
                String kind = error instanceof IllegalArgumentException ? "ValueError" : error.getClass().getSimpleName();
                if (Set.of("ParserException", "ScannerException", "ConstructorException", "ComposerException", "ReaderException").contains(kind))
                    kind = kind.replace("Exception", "Error");
                result.warnings.add("Configuration is unknown in " + relative + ": " + kind);
            }
        }
        return result;
    }

    private record Evaluation(Boolean state, List<ObjectNode> evidence) { }
    private static ObjectNode evidence(String file, int line, String signal) {
        return JSON.createObjectNode().put("file", file).put("line", line).put("signal", signal);
    }
    private static int lineAt(String text, int end) { return 1 + (int) text.substring(0, end).chars().filter(c -> c == '\n').count(); }
    private static List<ObjectNode> routes(SourceFile file) {
        List<ObjectNode> found = new ArrayList<>();
        Matcher mappings = regex("@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\s*\\(([^)]*)\\)").matcher(file.text);
        while (mappings.find()) {
            String arguments = mappings.group(1);
            Matcher named = regex("\\b(?:path|value)\\s*=\\s*(\\{[^}]*\\}|\"(?:\\\\.|[^\"\\\\])*\")").matcher(arguments);
            String part = named.find() ? named.group(1) : arguments.split("=", 2)[0];
            List<String> paths = new ArrayList<>();
            Matcher strings = regex("\"([^\"\\n]+)\"").matcher(part);
            while (strings.find()) paths.add(strings.group(1));
            for (String path : paths) if (path.startsWith("/") && !path.endsWith("/") && !paths.contains(path + "/"))
                found.add(evidence(file.file, lineAt(file.text, mappings.start()), "unpaired route " + path));
        }
        return found;
    }
    private Evaluation evaluate(JsonNode rule, Pom pom, Inventory files) throws IOException {
        checkTime();
        var field = rule.fields().next();
        String operation = field.getKey();
        JsonNode value = field.getValue();
        List<ObjectNode> collected = new ArrayList<>();
        if (operation.equals("all") || operation.equals("any")) {
            boolean all = operation.equals("all"), decisive = false, unknown = false;
            for (JsonNode child : value) {
                Evaluation result = evaluate(child, pom, files);
                collected.addAll(result.evidence);
                unknown |= result.state == null;
                decisive |= Boolean.valueOf(!all).equals(result.state);
            }
            return new Evaluation(decisive ? Boolean.valueOf(!all) : unknown ? null : Boolean.valueOf(all), collected);
        }
        if (operation.equals("not")) {
            Boolean state = evaluate(value, pom, files).state;
            return new Evaluation(state == null ? null : !state, collected);
        }
        if (operation.equals("dependency")) {
            for (Dep d : pom.dependencies) for (JsonNode coordinate : value)
                if ((d.group + ":" + d.artifact).equals(coordinate.asText())) {
                    collected.add(evidence(pom.file, 1, "dependency " + d.group + ":" + d.artifact)); break;
                }
            return new Evaluation(!collected.isEmpty(), collected);
        }
        if (!files.provided) return new Evaluation(null, collected);
        if (operation.equals("property")) {
            boolean uncertain = !files.complete;
            for (var property : files.properties.entrySet()) {
                if (!regex(value.path("key").asText()).matcher(property.getKey()).matches()) continue;
                List<Property> entries = property.getValue();
                if (entries.stream().anyMatch(e -> e.conditional || e.value.contains("${"))
                        || entries.stream().filter(e -> !e.conditional).map(Property::value).distinct().count() > 1) {
                    uncertain = true; continue;
                }
                for (Property entry : entries)
                    if (Pattern.compile(value.path("value").asText(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS).matcher(entry.value).matches())
                        collected.add(evidence(entry.file, entry.line, "property " + property.getKey() + " (value omitted)"));
            }
            return new Evaluation(!collected.isEmpty() ? Boolean.TRUE : uncertain ? null : Boolean.FALSE, collected);
        }
        for (SourceFile file : files.files) {
            if (operation.equals("unpaired_route") && file.code) collected.addAll(routes(file));
            else if (operation.equals("source_regex") && file.code) signatures(collected, file, value.asText(), "source signature");
            else if (operation.equals("file_regex") && glob(value.path("glob").asText(), file.name))
                signatures(collected, file, value.path("pattern").asText(), "resource signature");
        }
        return new Evaluation(!collected.isEmpty() ? Boolean.TRUE : files.complete ? Boolean.FALSE : null, collected);
    }
    private static void signatures(List<ObjectNode> found, SourceFile file, String pattern, String signal) {
        Matcher matches = regex(pattern).matcher(file.text);
        while (matches.find()) found.add(evidence(file.file, lineAt(file.text, matches.start()), signal));
    }
    private static boolean glob(String glob, String name) {
        // Catalog globs use '*', which (as in Python fnmatch) also crosses directories.
        String pattern = String.join(".*", Stream.of(glob.split("\\*", -1)).map(Pattern::quote).toList());
        return Pattern.compile(pattern, Pattern.DOTALL).matcher(name).matches();
    }
    private static List<BigInteger> version(String value) {
        if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return null;
        return Stream.of(value.split("\\.")).map(BigInteger::new).toList();
    }
    private static int compare(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < 3; i++) { int result = a.get(i).compareTo(b.get(i)); if (result != 0) return result; }
        return 0;
    }
    private static boolean within(List<BigInteger> value, JsonNode bounds, String min, String max) {
        return value != null && compare(value, version(bounds.path(min).asText())) >= 0 && compare(value, version(bounds.path(max).asText())) < 0;
    }

    public ObjectNode scan(Path pomPath, Path source, String toVersion) throws Exception {
        JsonNode catalog = loadCatalog();
        Pom pom = readPom(pomPath);
        Inventory files = inventory(source);
        ObjectNode report = JSON.createObjectNode().put("catalog_version", catalog.path("catalog_version").asText())
            .put("from_version", pom.boot).put("to_version", toVersion).put("coverage", "reviewed-boundary");
        Map<String, List<ObjectNode>> buckets = new LinkedHashMap<>();
        for (String name : List.of("findings", "unknown", "mitigation_hints")) buckets.put(name, new ArrayList<>());
        ArrayNode notMatched = report.putArray("not_matched");
        ArrayNode warnings = report.putArray("warnings");
        pom.warnings.forEach(warnings::add); files.warnings.forEach(warnings::add);
        report.putArray("limitations")
            .add("Potential means a local signature matched, not a reproduced runtime defect.")
            .add("No Maven execution or network calls. Transitives, external config, active beans, runtime URLs and deployment state are unknown.")
            .add("No match is not a compatibility certificate. Analyze effective production POMs and every deployed module/configuration.")
            .add("Mitigation hints are static and may belong to a different security chain or inactive configuration.");
        for (JsonNode item : catalog.path("drifts")) {
            checkTime();
            ObjectNode entry = item.deepCopy();
            entry.put("rank", RANKS.get(item.path("severity").asText()));
            JsonNode bounds = item.path("versions");
            if (!within(version(pom.boot), bounds, "from_min", "from_max_exclusive") || !within(version(toVersion), bounds, "to_min", "to_max_exclusive")) {
                report.put("coverage", "unknown"); entry.putArray("evidence");
                entry.put("reason", "Version is unknown or outside the reviewed 2.7.x to 3.0.x boundary."); buckets.get("unknown").add(entry); continue;
            }
            Evaluation state = evaluate(item.path("detection").path("when"), pom, files);
            Evaluation mitigation = evaluate(item.path("detection").path("mitigated_by"), pom, files);
            entry.set("evidence", JSON.valueToTree(state.evidence));
            if (state.state == null) {
                entry.put("reason", "Required source/configuration evidence is missing, conditional or incomplete."); buckets.get("unknown").add(entry);
            } else if (state.state && Boolean.TRUE.equals(mitigation.state)) {
                ((ArrayNode) entry.get("evidence")).addAll(mitigation.evidence);
                entry.put("reason", "A compatibility setting was observed; confirm it applies to this code path."); buckets.get("mitigation_hints").add(entry);
            } else if (state.state) { entry.put("confidence", "potential"); buckets.get("findings").add(entry); }
            else notMatched.add(item.path("id").asText());
        }
        for (var bucket : buckets.entrySet()) {
            bucket.getValue().sort(Comparator.comparingInt((ObjectNode item) -> item.path("rank").asInt()).thenComparing(item -> item.path("id").asText()));
            report.set(bucket.getKey(), JSON.valueToTree(bucket.getValue()));
        }
        return report;
    }
}
