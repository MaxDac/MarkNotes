@AGENTS.md

## Claude Code notes

- `AGENTS.md` (imported above) is the source of truth; put shared guidance there, not here.
- This is a Windows-friendly repo: on Windows use `.\gradlew.bat`; Gradle output is long, so pass `-q` or
  `--console=plain` and read the report under `**/build/reports/` when a task fails.
- Use `/parity-check <feature>` (`.claude/commands/parity-check.md`) before and after changing anything in
  the cross-app parity areas listed in `AGENTS.md`.
- Read sibling repos (marknotes-electron, marknotes-web, marknotes-spec) with `gh` rather than cloning them.
