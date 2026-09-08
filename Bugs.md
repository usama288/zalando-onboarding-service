# Known bugs

Verified against a running instance, not inferred from reading code.

Fixed entries are removed rather than struck through, and their identifiers are not reused — a
gap in the numbering means that bug was fixed, and the commit that fixed it is the record.
Only B5 remains, and it is closed as a documentation defect rather than a code change.

---

## B5 — Email is specified as editable, and cannot be edited

> **RESOLVED: documented, not fixed.** The defect was in the document, not the code.

ASSUMPTIONS A7 claimed email is editable *because a mistyped address must be recoverable*,
which promised a capability V1 does not expose. A7 now states the actual position: email is
not immutable at the domain level — `Application.changeApplicantEmail` enforces the draft-only
rule — but nothing exposes it, and recovering from a typo means starting a new draft, which A7
already establishes is harmless because uniqueness is enforced only at submit.

`changeApplicantEmail` and its test are kept deliberately. It is the seam: exposing the edit is
a `PATCH /api/applications/{draftToken}` and a control on the review screen, not a redesign.

**Still true, and still worth knowing:** an abandoned draft continues to hold the mistyped
address until a retention policy exists (A14, not implemented).

**Note.** CLAUDE.md carries the same "IS editable" phrasing. That file is the design contract
rather than a description of the build, so it was left alone.
