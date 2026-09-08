# Assumptions & Tradeoffs

Self-service onboarding MVP for solo entrepreneurs (Germany, Poland, Netherlands).
Recommended timebox: 4–5 hours.

This document records what was assumed, what was deliberately **not** built, and why.
Where the brief was ambiguous, the interpretation is stated rather than silently chosen.

---

## 1. How the brief was interpreted

Three points in the brief pull against each other. Resolved as follows.

| Tension | Resolution |
|---|---|
| *"MVP by the end of the month"* vs. a 4–5 hour timebox | The timebox is the real constraint. The month is the roadmap — see §7. |
| *"No compliance, tax, or AML decisioning"* (out of scope) vs. the **Decision data** columns and *"tests around flow transitions, and decisioning"* | No **real** decisioning. A mocked, deterministic decision component runs after submission and is unit-tested. It is never surfaced to the applicant in V1. |
| *"No login or authentication of any kind"* vs. **FR2** (resume) and **FR5** (retrieve by reference) | Unguessable tokens act as bearer credentials. No accounts, no login, no sessions. Tradeoff recorded as **T-3**. |
| *"No notifications (email/SMS)"* vs. a resume link that has to reach the applicant somehow | The **port** is built, the **integration** is not. Links are written to an outbox, never sent. See **A18**. |

---

## 2. Flow and domain

### A1 — Decisioning happens after submission

The applicant submits, receives a reference, and the application is `SUBMITTED`. Mocked
checks (identity confidence, debt flags, final decision) are evaluated and persisted
after that point. There is no real-time decision shown during onboarding.

In production this step is asynchronous and event-driven. Here it runs synchronously at
submit time behind an interface, so it can move to a queue later without touching callers.
The result is persisted to the `decision` column and is never surfaced to the applicant.
The mock is deterministic — derived from stable input, never `Random` — so the tests around
it mean something.

### A6 — Country is chosen at creation and is immutable

Country selects the **flow definition** — which sections exist, which fields they carry,
and which validation rules apply. It is therefore captured at step 0 alongside email,
before any section is filled, so the application has a known shape from its first write.

```
  Country ═══► selects flow definition
     │
     ▼
  ┌────────┬─────────┬──────┬───────────────────┬─────────┬─────────┐
  │Personal│ Address │ Tax  │ Registry (varies) │ Payment │ Consent │
  └────────┴─────────┴──────┴───────────────────┴─────────┴─────────┘
                              DE → Handelsregister
                              PL → CEIDG
                              NL → KvK (eenmanszaak)
```

Country is **not** editable after creation. Changing it would invalidate any already
completed country-specific section, and cascade-invalidation is not worth its cost here.
To change country, start a new application.

**Assumed:** country of residence and country of business registration coincide. A German
resident operating a Polish sole proprietorship is a real case that V1 does not handle.

### A3 / A4 — Forward navigation is gated, backward navigation is not

```
 ┌──────────────── DRAFT ─────────────────┐   ┌──── SUBMITTED ────┐
 │                                        │   │                   │
 │   step 1 ──► step 2 ──► step 3 ──► …   │   │    immutable      │
 │     ▲          ▲                       │   │                   │
 │     └──────────┘                       │   │                   │
 │   go back and correct : ALLOWED        │   └───────────────────┘
 │   skip ahead          : BLOCKED        │
 └────────────────────────────────────────┘
```

A step cannot be reached until the preceding one is complete. The server owns this gate;
the frontend does not decide it.

Completed steps **remain editable while the application is a draft**. Locking them would
mean one typo in step 1 is only recoverable by abandoning the application — unacceptable
in a funnel whose purpose is measuring drop-off.

Once submitted, the application is immutable. Editing and withdrawal are out of scope.

### A5 — Step order is fixed

Order is part of the flow definition, not something the applicant controls. Reordering is
out of scope for the user; the configuration still expresses order declaratively so the
three countries can differ.

### A16 — Only completed steps are persisted

```
  in-browser (transient)              server (durable)
  ──────────────────────              ────────────────────────────
  typing within step N        ──►     nothing
  Next → validation passes    ──►     section merged into form_data
  tab closed mid-step         ──►     step N lost; steps 1..N-1 intact
```

There is no autosave and no partial-section state. The server only ever holds validated
section data.

**Consequence:** what the row records is a *fact* — `last_completed_step`, the last
section actually completed, `NULL` on a fresh draft. Where to resume is **derived** from
it on the way out and never stored:

```
  last_completed_step = NULL      →  resume at PERSONAL_DETAILS
  last_completed_step = ADDRESS   →  resume at TAX_INFORMATION
  last_completed_step = CONSENT   →  resume at REVIEW
```

`REVIEW` is deliberately absent from the column's allowed values. It is a screen, it
persists nothing, and including it would make the column track *position* rather than
*progress* — and viewing position is client state (it belongs in the URL).

Re-saving an earlier section (**A4**) never moves `last_completed_step` backward; it
advances only when the section just completed is the next uncompleted one.

---

## 3. Data and state

### A2 — One table; all section data in a single JSONB column

Every section lives under its own key in `applications.form_data`. Section state is
binary: the key is present (completed and valid) or it is absent.

```
 form_data {
   "personalDetails": { …, "completedAt": "…" },
   "address":         { …, "completedAt": "…" },
   …
 }
```

A per-section table was considered and rejected (**T-10**). One table, one entity, one
repository is the right size here, and it makes **FR5** a single read.

Two things the blob obliges:

- **`schema_version`** — how to interpret the JSON after its shape changes. This is *not*
  flow versioning (**A21**, rejected); the blob is what creates the need.
- **`row_version`** (JPA `@Version`) — two tabs saving different sections write the same
  row, so optimistic locking replaces the conflict-freedom a per-section table would have
  given for free.

Each section carries its own **`completedAt`**. Without it, "where do applicants drop off
and how long does each step take" is unanswerable from the data — and that is the entire
question this MVP exists to answer.

### Status model

```
  DRAFT ──submit──► SUBMITTED
```

Two states, one transition. **FR6** requires no more, and none are invented.

### A10 — Submit is idempotent

A double-click must not mint two references. The email constraint does **not** cover this:
submit is an `UPDATE` on an existing draft row, so no uniqueness check fires.

```sql
UPDATE applications
   SET status = 'SUBMITTED', reference = :ref, submitted_at = now()
 WHERE id = :id AND status = 'DRAFT'
```

```
  rows = 1  → first writer wins, return the new reference
  rows = 0  → already submitted, load and return the EXISTING reference
```

The reference is generated **inside** the transition, never before it.

### A7 — Email is an application attribute, unique across submitted applications

Email is captured at step 0 and stored on the `applications` row itself — not as a field
inside the Personal Details section. There is no projection to keep in sync; the column is
the single source of truth.

```
  UNIQUE (email) WHERE status = 'SUBMITTED'
```

The filter is **required**, not stylistic. Email exists on every draft, so an unfiltered
constraint would let anyone lock a person out of onboarding simply by starting a draft with
their address. Scoping it to submitted applications means the rule reads *"one submitted
application per email"* rather than *"one draft in existence per email."*

Uniqueness is therefore checked at submit. Duplicate drafts are permitted and harmless.

Country and email are both captured at step 0 but behave differently:

| | Changeable within a draft? | Why |
|---|---|---|
| **country** | No, by design | selects the flow — changing it strands completed sections |
| **email** | Not in V1, but not immutable either | shapes nothing, so nothing prevents it |

Email is not immutable at the domain level — `Application.changeApplicantEmail` enforces the
draft-only rule — but V1 exposes no endpoint or UI to change it. Recovering from a mistyped
address means starting a new draft, which A7 already establishes is harmless, since uniqueness
is enforced only at submit. Exposing the edit is a follow-up, not a redesign.

**Known limitation (T-4):** with no authentication, any *on-screen* "this email has already
applied" message is an enumeration oracle. V1 avoids this by never looking an email up on
screen — see **A19**.

### A11 — Consent is a country-specific section

Consent is not a separate table and not a boolean. It is a **section like any other**, and
the set of consents it requires is declared per country in the flow definition:

```
  DE                        PL                        NL
  ──────────────────        ──────────────────        ──────────────────
  termsOfService            termsOfService            termsOfService
  dataProcessing            dataProcessing            dataProcessing
  creditCheck (SCHUFA)      creditCheck (BIK)         creditCheck (BKR)
```

Bureau names are illustrative and mocked, per the brief's *"mock reasonable market-specific
checks."* The point is that the **set** of required consents is configuration, not code —
exactly like the registry section.

Each accepted consent is stored in the section payload as `{ version, acceptedAt }`.
Consent **text** lives outside the application; only the version identifier is recorded.

The credit-check consent is load-bearing: the mocked decision data includes debt flags, so
this consent is what gates the decisioning step (**A1**) in the layer-2 cross-section rules.

This keeps **one persistence shape for every step** — no special case.

---

## 4. Validation

### A17 — Validation is server-side and layered

```
 PUT /applications/{id}/sections/{step}
     │
     └─► layer 1 — field validation
         required, format, IBAN checksum, per-country tax/registry patterns
         ✗ → 400 + violations[], nothing persisted
         ✓ → section merged into form_data

 POST /applications/{id}/submit
     │
     └─► layer 2 — completeness and cross-section
         all sections required by THIS country's flow present?
         cross-section rules (e.g. credit-check consent present)
         ✗ → 400 + violations[]
         ✓ → conditional transition → reference
```

Layer 2 is not redundant. There is no authentication, so a client can call `/submit`
directly against a half-filled application. **FR3** is satisfied here.

Both layers return the same contract (RFC 7807 `application/problem+json`) so the frontend
has a single rendering path:

```json
{
  "type": "https://.../validation-error",
  "title": "Validation failed",
  "requestId": "…",
  "violations": [
    { "field": "taxNumber", "code": "PATTERN_INVALID",  "message": "…" },
    { "field": "iban",      "code": "CHECKSUM_INVALID", "message": "…" },
    { "section": "kvk",     "code": "SECTION_MISSING",  "message": "…" }
  ]
}
```

Machine-readable `code`, human-readable `message` — the *"clear reason per field"* the
brief asks for.

### A12 — IBAN is validated offline only

```
  raw input
     │  normalise: strip spaces, uppercase
     ▼
  1. structure   AA99 + alphanumeric BBAN
  2. country     valid ISO 3166-1 alpha-2
  3. length      matches that country's expected length
  4. checksum    ISO 7064 mod-97 == 1
```

This proves the number is **well-formed**. It does not prove the account exists, is open,
or belongs to the applicant. Bank account verification is out of scope.

An IBAN whose country differs from the application country is **not** rejected — SEPA makes
a German sole trader with a French account entirely normal.

---

## 5. Access, resume and verification

| | |
|---|---|
| **A8** | Resume (**FR2**) uses an unguessable draft token issued at creation. |
| **A9** | The submitted reference (**FR4/FR5**) is unguessable and non-sequential. |

Both are bearer credentials: whoever holds the token can see the application. This follows
directly from *"no login or authentication of any kind"* and is recorded as **T-3**.

### A18 — Resume links go through a notification port, not a provider

| Built | Not built |
|---|---|
| `NotificationPort` interface | SMTP / provider account |
| a "resume link issued" event | templates, localisation, deliverability |
| an outbox adapter that **records** the link | bounce and retry handling |

Links are written to an outbox table in the same transaction that creates the draft, and
never sent. A dev-profile endpoint exposes the outbox so resume can be demonstrated end to
end. The outbox — not a log line — is deliberate: the resume token is a bearer credential
and logging it would contradict **A13**.

### A19 — Email is never used to resume an application on screen

Entering an email always starts a **new** draft. Earlier drafts remain reachable through the
link already issued for them.

Resuming by email would mean anyone who knows an applicant's address could read their name,
date of birth, tax number and IBAN. The only honest fix is proving ownership of the address,
which requires the notifications that are out of scope.

A useful consequence: because nothing is looked up, the on-screen response is identical
whether or not a draft exists — so there is no enumeration oracle at the entry point.

### A20 — Email verification is out of scope, but the flow is shaped for it

Collecting personal and financial data against an *unverified* address means a typo or a
malicious entry stores someone's data against a contact that is not theirs. Verify-first is
the correct behaviour; it is also weak authentication, which the brief excludes.

The resume link and a verification link are **the same artifact**. The difference is only
whether the form is gated on clicking it:

```
 V1   email → create draft → issue link → continue immediately
                                  └─ link exists, is not required

 V2   email → create draft → issue link → "check your email"
                                  └─ link is the ONLY way in
                                     ⇒ email verified by construction
```

V2 is **one guard on section writes** — no new subsystem, no schema change, no
`emailVerifiedAt` column. Clicking the link is the verification, and the token already
proves it.

## 6. Operational

### A13 — Logging carries identifiers, not personal data

| In scope | Out of scope |
|---|---|
| `requestId` in MDC, echoed as a response header | Field-level encryption at rest |
| Request/response bodies never logged | Tokenisation / PII vault |
| Only `applicationId` and `reference` logged | Key management and rotation |
| `toString()` excludes PII fields | GDPR erasure / subject-access endpoint |
| IBAN shown and logged as last 4 only | Redaction in the log pipeline |
| Resume tokens go to the outbox, never a log | Audit log of PII access |

The left column is a servlet filter and a logging convention. The right column is a
different project.

### A14 — Retention is stated, not implemented

Abandoned drafts would expire on a TTL; submitted applications would be retained per the
relevant regulatory period. `createdAt` / `updatedAt` are recorded so a policy can be
applied later. No expiry job is built.

### A21 — Flow definitions are not versioned per application

Definitions are loaded at startup and ship with the artifact, so they cannot change under a
live draft without a restart, and drafts are short-lived. Pinning a flow version to each
application was considered and rejected as speculative at this size.

**Limitation:** if a definition did change while drafts were in flight, those drafts could
reference a section that no longer exists. Pinning becomes necessary when flow configuration
becomes runtime-editable.

### A15 — Single locale

The form is English-only. Country selection changes fields and validation rules, not
language. Localisation is a follow-up.

---

## 7. Tradeoff register

| ID | Decision | Alternative considered | Why this way |
|---|---|---|---|
| **T-1** | Mocked decisioning, run synchronously behind an interface | Async worker / queue | A queue is the production answer, but it costs infrastructure the timebox cannot afford. The interface is the seam. |
| **T-2** | Only completed steps are persisted | Autosave per field | Removes partial state and draft-conflict handling entirely. Cost: input lost if a tab closes mid-step. |
| **T-3** | Unguessable tokens as bearer credentials | Any form of authentication | Explicitly out of scope. The security property rests entirely on token entropy. |
| **T-4** | Email uniqueness enforced at submit | Allow duplicates and flag for review | Gives **FR3** a clean rejection and is demoable. Accepts an enumeration oracle. |
| **T-5** | Country immutable after creation | Cascade-invalidate downstream sections on change | Cascade logic is not affordable here, and "start a new application" is an honest V1 answer. |
| **T-6** | Flow differences expressed as configuration | `if (country == DE)` branches | Adding a fourth country should be a configuration change, not a code change. |
| **T-7** | Notification **port** built, integration stubbed to an outbox | Skip notifications entirely | The resume link has to go somewhere. A port plus a recording adapter costs ~30 minutes and turns a missing feature into a one-line adapter swap. |
| **T-8** | No email verification in V1; link issued but not required | Gate the form on the link | Verification is weak auth and needs notifications — both out of scope. The upgrade is one guard, documented in **A20**. |
| **T-9** | Flow definitions unversioned | Pin a flow version per application | Speculative at this size; config ships with the artifact. Limitation stated in **A21**. |
| **T-10** | One table, all sections in one JSONB column | A row per section in an `application_sections` table | One entity and one repository fits the timebox, and **FR5** becomes a single read. Cost: concurrent saves to different sections contend on one row, handled with optimistic locking, and the blob needs `schema_version` to stay readable. |

---

## 8. Deliberately not built

Out of scope per the brief, and not attempted:

- Authentication, accounts, sessions
- Real eID, KYC, company registry, credit bureau or banking integrations
- Real compliance, tax or AML decisioning
- Editing or withdrawing a submitted application
- Notifications (email / SMS)
- Any internal review, approval or rejection workflow

### The month, if it were real

Ordered by what the MVP is meant to learn:

1. Funnel instrumentation — drop-off per step, time-to-complete, abandonment point
2. A real notification adapter behind `NotificationPort`, which immediately unlocks (3)
3. Email verification — gate section writes on the issued link (**A20**)
4. Real registry and identity integrations, one country at a time, behind the existing seams
5. Async decisioning, and an operations review queue
6. Rejection handling and re-application
7. Localisation, retention enforcement
