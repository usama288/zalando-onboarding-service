# Onboarding MVP — design contract

## What this is
Self-service onboarding for solo entrepreneurs in DE, PL and NL. An applicant fills a
multi-step form, resumes it later, and submits it. Timebox: ~5 hours. Judgement and
clarity matter more than surface area.

## Hard scope boundaries — do not build these
- No authentication, accounts, sessions or login of any kind.
- No real eID, KYC, company registry, credit bureau or banking integrations.
- No real compliance, tax or AML decisioning. Decisioning is mocked.
- No editing or withdrawing an application after submission.
- No email or SMS is ever actually sent.
- No review, approval or rejection workflow.
- Exactly two statuses exist: DRAFT and SUBMITTED. Never invent others.

## Domain rules
- Country is chosen when the application is created and is IMMUTABLE. It selects the
  flow definition. Email is captured at the same time and IS editable.
- Steps are ordered. A step cannot be reached until the previous one is complete
  (forward gating), but completed steps can be re-edited while the application is a
  draft (backward editing).
- Only completed, validated steps are persisted. There is no autosave and no partial
  section state.
- furthest_step means the furthest step reached, never "currently viewing". Viewing
  position is client state.
- Submission is immutable and idempotent.
- Email is unique across SUBMITTED applications only. Duplicate drafts are allowed and
  harmless — enforcing uniqueness on drafts would let anyone lock a person out.

## Validation is layered
- Layer 1, on saving a section: field-level (required, format, checksums, per-country
  rules). Nothing is persisted if it fails.
- Layer 2, on submit: completeness for this country's flow, plus cross-section rules.
  Layer 2 is NOT redundant — there is no auth, so a client can POST submit directly
  against a half-filled application.
- Both layers return the same RFC 7807 application/problem+json body with a
  violations[] array of {field | section, code, message}.

## Country variation has three shapes; the config must handle all three
1. Same fields, different rules — postcode, tax id formats.
2. Different fields entirely — the business registry section.
3. Different item set — which consents are required.
Flow definitions are DECLARED in YAML and IMPLEMENTED by named Java validator beans.
Country rules are never `if (country == DE)` branches and never database constraints.

## Persistence
One `applications` table. All section data lives in a single `form_data` JSONB column.
`schema_version` exists to interpret that blob after its shape changes — this is not
flow versioning, which was deliberately rejected.

## Production mindset
- Validate server-side, always.
- Never log personal data or bearer tokens. Log applicationId, reference and requestId.
- Every response carries a requestId so support can trace an application.
