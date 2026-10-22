# MarkNotes note format

MarkNotes (Android), MarkNotes desktop (Electron) and MarkNotes web share the same notes folder through the
linked drives (Proton Drive, Dropbox, OneDrive). This document describes the on-disk conventions all three
apps follow so their files interoperate.

## Notes and folders

A note is a UTF-8 `.md` file; folders are real directories. The note title is the file name without `.md`
and its full path (for example `Work/Projects/Roadmap`) is the folder path plus the title.

## Note IDs (front-matter)

Every note has a stable ID stored in a YAML front-matter block at the **very top** of the file:

```markdown
---
id: k7f3q2xa
---
# My note
```

- The ID is 8 random characters from the alphabet `0123456789abcdefghjkmnpqrstvwxyz` (Crockford base32 in
  lowercase, without `i`, `l`, `o` and `u`).
- It is assigned when a note is created. Notes without an ID (created before this feature or by another tool)
  get one lazily, the first time they are opened in the editor or listed in the `@` popup. The ID is written
  back through the normal save path, so it syncs like any other edit.
- Other front-matter keys are preserved verbatim; `id` is added as the first key when missing.
- The front-matter is **hidden** in the editor and in the preview: users only edit the body, and the header
  is re-attached on save.
- Duplicate IDs (for example after copying a file): links resolve to the first note by sorted path. When an
  app detects a duplicate locally it assigns a new ID to the later note(s).

## Note links

A link to another note uses the `marknotes:` scheme with the target's ID:

```markdown
See [Roadmap](marknotes:k7f3q2xa).
```

The link text is the target's title at the time the link was inserted (`[`, `]` and `\` are escaped with a
backslash). Because links use the ID, they survive renames and moves. In the preview, clicking a link opens
the note; a link whose ID matches no note is styled as broken and clicking it shows "Note not found".

In the editors, typing `@` at the start of a line or after whitespace opens a picker listing every note with
its title, full path and ID (with a copy button). The text typed after `@` filters by ID prefix or by a
case-insensitive substring of the title/path; choosing a note replaces `@query` with the link.

## Images

Images live in a single `attachments/` folder at the **root** of the notes folder:

```
attachments/<sha256>.<ext>
```

- `<sha256>` is the lowercase hex SHA-256 of the stored (final) bytes, so identical images are stored once.
- `<ext>` comes from the MIME type; only `png`, `jpg` (JPEG), `gif` and `webp` are accepted.
- Before storing, images are downscaled so their longest side is at most 2048 px, keeping their format.
  GIFs are stored untouched. Images larger than 10 MB after processing are rejected.
- The markdown inserted in the note is `![<original file name without extension>](attachments/<sha256>.<ext>)`.
  The path is always resolved from the notes root, whatever folder the note is in.
- The `attachments/` folder is reserved (no note or folder can be created or renamed to it) and hidden from
  the note tree.
- "Clean unused images" (a manual action in Settings) scans all notes for `attachments/…` references and
  deletes unreferenced files locally; the deletions propagate on the next sync. It never runs automatically.

## Sync of attachments

Files under `attachments/` are synced to **every** linked drive, alongside the notes, and listed in the drive's
`.marknotes/manifest.json` with their SHA-256 like notes (the manifest stays `format: 1`). They are binary and
immutable (the name is the content hash), so they are handled differently from notes:

- they are only uploaded, downloaded or deleted, never three-way merged, and never produce conflicts;
- no base copy is kept for them;
- deletions propagate like note deletions.
