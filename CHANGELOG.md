# Changelog

## 0.2.0 — 2026-09-21 (local, unpublished)

- Port Maven runtime scanning to JDK 17; remove Python execution and `compat.python`.
- Parse POMs using Maven model; shade and relocate Jackson and SnakeYAML in the JAR.
- Preserve all 12 catalog rules, ranking, evidence, uncertainty, version boundaries,
  report fields (`schema_version: "1"`) and local repair-request content.
- Keep the original Python scanner and bridge as test oracles; MCP remains an optional
  Python agent entry outside the Java build path.
- Add Java regression tests, all-example and all-rule Java/Python parity comparisons,
  and an installed Maven verification with an empty PATH (no Python executable).
- Retain findings/error gates; `compat.timeoutSeconds` now checks a cooperative JVM
  deadline between files and rule evaluations rather than killing a subprocess.
- No publication or push.

## 0.1.0 — 2026-09-21 (local, unpublished)

- Maven `scan` goal bound to `verify`, bundling the existing scanner and catalog.
- Human-readable console/file report and versioned JSON wrapper with exact core results.
- Warnings by default; configurable findings/error gates and scanner timeout.
- Local repair-request template for a paid compatibility patch/PR pilot; contact unknown.
- Independent stdio MCP tool using local POM/source paths without telemetry.
- Apache-2.0 distribution with the existing MIT scanner notice retained.
- Python runtime plus scanner dependencies required; no Central or GitHub release yet.
