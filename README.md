# Compat Foundry — Spring Boot drift checks in Maven

Free, local static diagnosis for Java teams moving **Spring Boot 2.7.x → 3.0.x**.
Add a Maven goal to `verify`: get ranked potential behavior risks, file evidence,
official sources and reviewable fixes in your normal build. Coding agents can use
that JSON or the [stdio MCP entry](mcp/server.py).

The catalog has 12 rules; the included Boot 2.7 sample produces 7 findings.
Findings are potential risks, not proven runtime defects. Only the trailing-slash
rule has the existing runtime baseline/upgrade/repair evidence; the other rules'
runtime status is unknown. No match is not a compatibility certificate.

## 30-second setup with the built-in Maven repository

Requires **JDK 17+ and Maven 3.6.3+**. No Python runtime or Python packages are required by the Maven plugin.
Add the repository hosted on this GitHub project's `maven-repo` branch:

```xml
<pluginRepositories><pluginRepository><id>compat-foundry</id><url>https://raw.githubusercontent.com/mkmkkkkk/compat-foundry-maven-plugin/maven-repo/</url></pluginRepository></pluginRepositories>
```

The JitPack setup remains available as a fallback if JitPack is working:

```xml
<pluginRepositories><pluginRepository><id>jitpack.io</id><url>https://jitpack.io</url></pluginRepository></pluginRepositories>
```

Dependencies may need downloading on first setup. Scanning itself is offline.
The shaded JAR bundles the Java scanner, Jackson, SnakeYAML and the unchanged
12-rule catalog. POM parsing uses Maven's own model. Scanning runs inside the JVM;
no interpreter, subprocess or checkout path is needed. The catalog remains at
`catalog_version: 0.1.0` and the JSON wrapper at `schema_version: "1"` for parity.

Paste inside your project's `<build><plugins>`:

```xml
<plugin>
  <groupId>dev.compatfoundry</groupId>
  <artifactId>compat-foundry-maven-plugin</artifactId>
  <version>0.2.0</version>
  <executions>
    <execution><goals><goal>scan</goal></goals></execution>
  </executions>
</plugin>
```

Run `mvn verify`. The goal's default phase is `verify`; its default policy is
**warn, do not fail the build**, including when the scanner is unavailable.
Inspect `status` and unknowns in JSON; successful Maven exit is not a clean scan.

For the included real Boot sample (with the plugin already configured):

```sh
mvn -f examples/boot27/pom.xml verify
```

For a scan without running the application's build/tests:

```sh
mvn dev.compatfoundry:compat-foundry-maven-plugin:0.2.0:scan
```

Reports go under `${project.build.directory}/compat-foundry/`:

- `report.txt`: human-readable evidence, sources and fixes; also printed by Maven.
- `report.json`: `{schema_version,status,summary,repair,scan}`; `scan` is the exact core report.
- `repair-request.md`: optional paid compatibility patch/PR pilot request template.

The sample overrides Maven's build directory, so its reports are in
`examples/boot27/target/2.7.18/compat-foundry/`.

| Property | Default | Meaning |
|---|---|---|
| `compat.failOnFindings` | `false` | Fail after writing reports if any potential risk matches |
| `compat.failOnError` | `false` | Fail if execution/input fails; independent from findings |
| `compat.toVersion` | `3.0.13` | Target version; outside reviewed bounds becomes unknown |
| `compat.pom` | `${project.file}` | Optional effective production POM |
| `compat.source` | `${project.basedir}/src/main` | Missing source produces unknowns |
| `compat.outputDirectory` | `${project.build.directory}/compat-foundry` | Use a separate directory per module |
| `compat.timeoutSeconds` | `60` | Positive cooperative JVM deadline, checked between files and rule evaluations |

Unknown findings do not trip `failOnFindings`; inspect JSON coverage separately.
No transitive resolution, inherited dependencies, profile activation or module
recursion is performed by the scanner. Bind in each module or supply its effective
production POM. Full matching limits: [scanner documentation](scanner/README.md).

## Repair pilot entry

Every successful report links to its local `repair-request.md`, listing matched
IDs and the requested runtime evidence + patch/PR deliverable. This is the future
free-diagnosis → paid compatibility pilot handoff, not an automatic checkout.
Public project contact and pricing: **unknown**. Fill and review the template
locally; nothing is uploaded or sent and no purchase occurs.

## Coding agents: MCP

Use [mcp/config.example.json](mcp/config.example.json), replacing both absolute paths.
The optional agent-side `mcp/server.py` still requires Python 3.9+ with
`scanner/requirements.txt` (PyYAML and jsonschema). It is outside the Java build
and runtime path. Set up that environment only if using MCP or running the
cross-language parity tests. It supports local stdio MCP
(protocol `2024-11-05`), `initialize`, `ping`, `tools/list`, and `tools/call`.
Tool `scan_spring_boot_drift` accepts:

```json
{"pom":"/your/project/pom.xml","source":"/your/project/src/main","to_version":"3.0.13"}
```

The tool returns the core JSON as text content. It reads local files accessible
to the client process; configure it only for trusted local agent clients.
It does not infer sample identity, upload code, start HTTP, or record inbound events.
The old `scanner/foundry_service.py`, web prototype and old MCP config are preserved
as historical artifacts; this plugin/MCP pair is the supported product surface.

## Source, checks and release status

[Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) ·
[CI workflow](.github/workflows/ci.yml)

Build and Java tests: `mvn install`. Cross-language tests (Python is test-only):
`python3 -m unittest discover -s maven-plugin/tests -v` after installing
`scanner/requirements.txt`. These compare all `scanner/examples` Maven samples,
all 12 rules with positive/negative cases, and uncertainty boundaries against
the preserved Python oracle. JSON fields and report text must match exactly.
Run `python3 maven-plugin/tests/verify_install.py` and
`python3 maven-plugin/tests/verify_no_python.py` for installed-artifact receipts;
the latter launches Maven with an empty PATH and absolute JDK/Maven paths.

Apache-2.0 for new distribution code; the original scanner retains MIT, with its
notice included in the JAR. See [LICENSE](LICENSE), [NOTICE](NOTICE), and
[scanner/LICENSE](scanner/LICENSE). Maven Central is not used.

## Earlier compatibility lab (retained)

# Compat Foundry offline compatibility lab

This is a self-contained, standard-library-only compatibility experiment.

Run the suite from this directory:

```text
python -m unittest discover -s tests -v
```

Run a human-readable replay report:

```text
python -m lab.cli
```

The package fixtures are intentionally synthetic. `oldpkg` is the baseline,
`newpkg` contains six compatibility breaks, and `shim_pkg` is the independently
importable compatibility layer.
