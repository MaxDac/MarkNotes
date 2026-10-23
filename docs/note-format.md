# MarkNotes note format

The note format shared by MarkNotes for Android, desktop and web now lives in
[marknotes-spec](https://github.com/MaxDac/marknotes-spec): see
[note-format.md](https://github.com/MaxDac/marknotes-spec/blob/main/note-format.md), checked out here at
[`libre/spec/note-format.md`](../libre/spec/note-format.md) (run `git submodule update --init --recursive`).

Change the format in marknotes-spec first, then mirror it in the apps (marknotes-android, then a `libre/` bump
here; AGENTS.md, "Cross-app parity").
The spec's golden fixtures run against this app in the "Parity (marknotes-spec fixtures)" check.
