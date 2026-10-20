# CLAUDE.md

@AGENTS.md

Claude Code expands the import above; it summarizes commands and rules. Notes specific to working
here through tool calls:

- `build/` directories mirror sources (merged manifests, intermediates). Prefer the Grep tool, or
  pass `--exclude-dir=build` to `grep -r`.
- Verify a change with the narrowest Gradle task (`:src:<lang>:<name>:compileDebugKotlin` with
  `-Pextensions=<lang>:<name>`), then run that module's tests.
- Each extension is an independently versioned artifact. Before editing a second module, confirm it
  is in scope: a stray edit means an unrelated version bump and rebuild.
- Recorded fixtures must be sanitized; never add live credentials or personal data to tests.
