# Contributing

Use JDK 17+ and Maven 3.6.3+ for the plugin. Python 3.9+ with
`scanner/requirements.txt` is needed only for cross-language tests and optional MCP.
The scanner/catalog remain the single source of detection rules. Keep adapters thin;
preserve unknowns, official sources, evidence and reviewed version boundaries.
Static findings must not be described as reproduced runtime defects.

From the repository root:

```sh
mvn install
python3 -m unittest discover -s maven-plugin/tests -v
PYTHONPATH=scanner python3 -m unittest discover -s scanner/tests -p test_scanner.py -v
PYTHONPATH=scanner python3 -m unittest discover -s scanner/tests -p test_boundaries.py -v
python3 maven-plugin/tests/verify_install.py
python3 maven-plugin/tests/verify_no_python.py
```

The install verifier runs the real Boot sample's tests and verify lifecycle, compares
the packaged output with the core, checks failure policies and inspects the installed JAR.
It uses Maven offline after the build dependencies and sample dependencies are cached;
see `--online` for first-time dependency resolution. No listening server is started.
It preserves logs and generated fixtures under `evidence/plugin-0.2.0` and `target`.

New behavior needs a failing test first and a passing rerun with real receipts.
Do not bypass hooks, publish, push, edit unrelated work or use deletion commands.
Move disposable artifacts to `~/.Trash/` when cleanup is needed.
Commit email: `yangzk01@gmail.com`; include
`Co-Authored-By: gpt-6-astra <noreply@openai.com>` when applicable.

Publication, artifact coordinates/domain ownership and public support/contact setup
require a separate maintainer decision. No publication credentials belong in this repo.
