# Backend build — prompt log

Prompts used to build the onboarding backend with Claude Code, in order.

Companion document: `ASSUMPTIONS.md` (assumption IDs **A1–A21**, tradeoff IDs **T-1–T-9**
referenced throughout are defined there).

## At a glance

| Phase | Produced | Commit |
|---|---|---|
| **P0** | Gradle + Spring skeleton, compose stack, and `CLAUDE.md` — the design contract every later prompt inherits | `P0: bootstrap and design contract` |
| **P1** | Flyway `V1`, entity, repository, token and reference generation | `P1: schema and domain model` |
| **P2** | Flow definition model, DE/PL/NL YAML, fail-fast startup validation | `P2: flow configuration` |
| **P3** | Field validators and their checksum vectors | `P3: validators` |
| **P4–P6** | Service and state machine, REST API, error contract, notification outbox | `P4-P6: application service, REST API, notification outbox` |
| **PF** | Frontend webapp, every field rendered from the flow definition | `PF: frontend webapp` |
| **P6–P7** | Decision engine and test pass | `P6-P7: decision engine and test pass` |
| **P8** | README and architecture notes | `P8: README and architecture notes` |
| — | Fixes for the reproduced issues in `Bugs.md` | `fix: known issues B2, B3, B4, B6, B7, B8` |

Two phases are lettered or merged rather than following the plan. Both departures are
recorded where they happened, with the reason.

---

## How this pack is meant to be run

```
  P0 ──► P1 ──► P2 ──► … ──► P9
   │      │      │
   └── verify between every step; do not batch prompts
```

Each prompt ends with acceptance criteria. Run the verification, fix what fails, **then**
move on. P0 writes `CLAUDE.md` so the design contract is loaded once rather than restated
in every prompt — that is what keeps P1–P9 short.

Guardrail used throughout: each prompt states not only what to build but **what not to
build**. The failure mode with an agent on a scoped task is not bad code, it is helpful
scope creep — invented statuses, speculative abstractions, "while I was here" refactors.

---

## P0 — Project bootstrap and design contract

**Goal:** a runnable skeleton plus the persistent context every later prompt relies on.

```text
Set up a Spring Boot 3 backend for a self-service onboarding MVP. Java 21, Gradle
(with wrapper), Postgres, Flyway, Spring Web, Spring Data JPA, Bean Validation,
Jackson, and iban4j. Add docker-compose bringing up Postgres plus the app, and a
multi-stage Dockerfile.

Package root: com.zalando.onboarding
Packages: api, api.error, domain, flow, validation, decision, notification, support

Then write CLAUDE.md at the repo root containing exactly the contract below, so you
and I share it for the rest of this build.

--- CLAUDE.md ---
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
- last_completed_step records the last section actually completed, and is NULL for a
  fresh draft. Where to resume is DERIVED from it, never stored. Viewing position is
  client state and never touches the database.
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
--- end CLAUDE.md ---

Do not write any domain code yet. Bootstrap, compose, Dockerfile and CLAUDE.md only.
```

**Verify:** `./gradlew build` passes; `docker compose up` starts Postgres and the app;
`CLAUDE.md` exists and reads correctly.

> The contract above is reproduced as it was sent. The live `CLAUDE.md` has since diverged
> in two places: the line *"Email … IS editable"* was corrected to say that email is
> editable at the domain level but that V1 exposes no endpoint or UI to change it (see
> `Bugs.md` B5), and the *Simplicity over cleverness* section recorded below was added
> after P3. The log preserves what was sent; the repository holds the contract as it now
> stands.

---

## P1 — Schema and domain model

**Goal:** the database shape and the entity, with illegal states unrepresentable.

```text
Write Flyway migration V1__init.sql and the matching JPA entity.

CREATE TABLE applications (
  id                UUID PRIMARY KEY,
  draft_token_hash  TEXT NOT NULL UNIQUE,
  reference         VARCHAR(24) UNIQUE,
  applicant_email   VARCHAR(320) NOT NULL,
  country           CHAR(2) NOT NULL CHECK (country IN ('DE','PL','NL')),
  status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT','SUBMITTED')),
  last_completed_step VARCHAR(30)
                      CHECK (last_completed_step IN ('PERSONAL_DETAILS','ADDRESS',
                        'TAX_INFORMATION','BUSINESS_REGISTRY','PAYMENT_DETAILS',
                        'CONSENT')),
  schema_version    SMALLINT     NOT NULL DEFAULT 1,
  form_data         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  decision          JSONB,
  row_version       BIGINT       NOT NULL DEFAULT 0,
  submitted_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT application_submission_state CHECK (
    (status = 'DRAFT'     AND reference IS NULL     AND submitted_at IS NULL) OR
    (status = 'SUBMITTED' AND reference IS NOT NULL AND submitted_at IS NOT NULL)
  )
);

CREATE UNIQUE INDEX ux_applications_email_submitted
  ON applications (applicant_email) WHERE status = 'SUBMITTED';

CREATE TABLE notification_outbox (
  id UUID PRIMARY KEY,
  application_id UUID NOT NULL REFERENCES applications(id),
  type VARCHAR(40) NOT NULL,
  recipient VARCHAR(320) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

Notes that matter:
- The partial unique index is deliberate. A plain UNIQUE(email) would block a second
  DRAFT, letting anyone lock a person out of onboarding by starting a draft with their
  address. Add a SQL comment saying so.
- We store the HASH of the draft token, never the token itself, so a database dump
  yields no working resume links. The plaintext token is returned to the caller once,
  at creation.
- row_version maps to JPA @Version. form_data is a single blob; two tabs saving
  different sections will conflict, and that is the accepted cost of one-table storage
  (see T-2 in ASSUMPTIONS.md).

- last_completed_step is nullable and holds only real sections. REVIEW is deliberately
  NOT in that list: it is a screen, it persists nothing, and putting it in the column
  would make it track position rather than progress. Resume is a derived function —
  NULL means PERSONAL_DETAILS, CONSENT (the last section) means REVIEW, otherwise the
  section after the one recorded.

Also add: ApplicationStatus and Country enums, an OnboardingStep enum matching the
CHECK list in order (six sections, no REVIEW), a Spring Data repository, and a SecureRandom-based generator for
the draft token (URL-safe, >=128 bits) and the reference (unguessable, non-sequential,
not a database sequence).

Do not add any field that is not listed above.
```

**Verify:** migration applies cleanly; try inserting a row with `status='SUBMITTED'` and
a null reference and confirm the CHECK rejects it.

---

## P2 — Flow configuration

**Goal:** the country-variation engine — the piece the whole design rests on.

```text
Build the flow configuration model and loader.

Java records: FlowDefinition(country, schemaVersion, List<SectionDefinition>),
SectionDefinition(id, step, title, List<FieldDefinition>),
FieldDefinition(name, type, required, pattern, maxLength, List<String> validators,
RequiredWhen requiredWhen), RequiredWhen(field, equals).

Define the interface FlowDefinitionRepository with findByCountry(Country). Implement
YamlFlowDefinitionRepository, loading classpath:flows/{DE,PL,NL}.yml once at startup
into an immutable map. The interface is the seam that lets flow definitions move to the
database later without touching callers — keep callers depending only on the interface.

Write the three YAML files. Sections in order: personalDetails, address, taxInformation,
businessRegistry, paymentDetails, consent.

Fields:
- personalDetails (all countries): firstName, lastName, dateOfBirth (validator: adult),
  nationality. Email is NOT here — it lives on the application row.
- address: street, houseNumber, postalCode, city. Country is NOT a field; it is fixed to
  the application country and rendered read-only. postalCode pattern differs:
  DE ^\d{5}$ · PL ^\d{2}-\d{3}$ · NL ^\d{4} ?[A-Za-z]{2}$
- taxInformation:
  DE  taxNumber (required), vatRegistered (boolean),
      vatId (validator: vatIdDe, requiredWhen vatRegistered = true)
  PL  nip (required, validator: nip), vatRegistered, vatEu (requiredWhen ...)
  NL  vatRegistered, btwId (requiredWhen ...)
- businessRegistry:
  DE  registered (boolean), registryType, registrationNumber and registerCourt
      (both requiredWhen registered = true), registeredBusinessName
  PL  regon (validator: regon), ceidgRegistrationDate, pkdCode
  NL  kvkNumber (validator: kvk), tradeName, sbiCode
- paymentDetails (all countries): accountHolder, iban (validator: iban)
- consent: each country lists its required consents as fields of type CONSENT with a
  version string. All three require termsOfService, dataProcessing and creditCheck.

Validate at startup that every validator name referenced in YAML resolves to a bean, and
fail fast with a clear message if one does not. A typo in config must not become a
silent no-op at runtime.

Do not implement the validators yet.
```

**Verify:** app starts and loads three definitions; deliberately mistype a validator name
and confirm startup fails with a readable message.

---

## P3 — Validators

**Goal:** the checksum and format logic, with the heaviest test coverage in the build.

```text
Implement validation.

interface FieldValidator { String name(); Optional<Violation> validate(String field,
Object value); }, with a ValidatorRegistry resolving beans by name().

Violation is a record: (String field, String section, String code, String message).
Codes are machine-readable SCREAMING_SNAKE constants — REQUIRED, PATTERN_INVALID,
CHECKSUM_INVALID, TOO_LONG, NOT_ADULT, SECTION_MISSING, SECTION_UNKNOWN.

Validator beans:
- iban      — normalise (strip spaces, uppercase), then structure, ISO 3166-1 country,
              country length and ISO 7064 mod-97 via iban4j. Do NOT reject an IBAN whose
              country differs from the application country: SEPA makes a German sole
              trader with a French account entirely normal. Add a comment saying so.
- nip       — Polish NIP: 10 digits, weights 6,5,7,2,3,4,5,6,7, mod 11, reject when the
              check digit computes to 10.
- regon     — Polish REGON: 9-digit weights 8,9,2,3,4,5,6,7 and 14-digit weights
              2,4,8,5,0,9,7,3,6,1,2,4,8, mod 11 with remainder 10 mapping to 0.
- kvk       — Dutch KvK number: exactly 8 digits.
- vatIdDe   — DE followed by 9 digits.
- adult     — ISO date, parses, and at least 18 years before today.

SectionValidator (layer 1) walks a SectionDefinition and returns List<Violation>:
required, requiredWhen, maxLength, pattern, then named validators. It must reject unknown
fields not present in the definition rather than silently storing them.

SubmissionValidator (layer 2) takes the application and its flow definition and returns
violations for any missing section, plus the cross-section rule that the creditCheck
consent must be present and accepted — that consent is what gates the mocked decisioning.

Write unit tests for every validator with real valid and invalid values, including
boundary cases: a NIP whose check digit computes to 10, a 9- and a 14-digit REGON, an
IBAN with lowercase and spaces, and a date of birth exactly 18 years ago today.

These validators are the most likely place for a subtle bug, so test them properly.
```

**Verify:** `./gradlew test` green; check a real published NIP/REGON test vector by hand.

---

## Mid-build amendment — after P3

Added to `CLAUDE.md` at this point rather than at P0, because the need for it only became
visible once the build had momentum: the validator phase attracted far more scrutiny than
its share of a five-hour budget, while the form webapp — which the brief calls the core of
the task — did not yet exist.

```text
## Simplicity over cleverness
This is a five-hour MVP. When two designs both work, take the smaller one.

Specifically, do not introduce: event sourcing, CQRS, a state-machine library, a rules
engine, HATEOAS, an API versioning scheme, a mapping framework, custom test DSLs, shared
abstract test base classes, or a frontend build step. Plain service methods, plain
records, hand-written mapping, one test class per behaviour.

Do not add an abstraction for a second case that does not exist yet. Three countries are
the only variation this system has.

Prefer finishing to polishing. An unfinished feature costs more than an unpolished one.
```

---

## P4 — Application service and state machine

**Goal:** the rules from `CLAUDE.md` expressed in one place.

```text
Write ApplicationService with these operations. Put the state rules here, not in
controllers.

createDraft(email, country)
  - normalise email (trim, lowercase); reject malformed
  - generate draft token, store only its hash, return the plaintext once
  - persist DRAFT with last_completed_step = NULL, empty form_data
  - record a RESUME_LINK entry in notification_outbox in the SAME transaction
  - never look up an existing application by email: entering an email always starts a
    new draft, because resuming by email alone would expose one person's data to anyone
    who knows their address (A19)

loadByDraftToken(token) — hash, look up, 404 on miss.

saveSection(token, sectionId, payload)
  - reject if status != DRAFT
  - reject if sectionId is not in this country's flow
  - forward gating: reject if the preceding section is not yet complete
  - backward editing: re-saving an already-complete section is allowed
  - run layer-1 validation; on failure persist NOTHING
  - on success merge the section into form_data with a completedAt timestamp, and
    advance last_completed_step only when the section just completed is the next
    uncompleted one. Re-saving an earlier section must never move it backward.

submit(token)
  - run layer-2 validation
  - transition with a conditional update:
      UPDATE applications SET status='SUBMITTED', reference=?, submitted_at=now()
       WHERE id=? AND status='DRAFT'
    rows=1 → this caller won, return the new reference
    rows=0 → already submitted, load and return the EXISTING reference
  - generate the reference INSIDE the transition, never before it, or a double-click
    mints two references
  - after a successful transition, run the mocked decision engine and store its result
    in the decision column

findByReference(reference) — read-only submitted view for FR5.

Each section stores a completedAt timestamp inside its own JSON object. This is what
makes the funnel measurable — time per step and where applicants abandon — which is the
entire point of the MVP.
```

**Verify:** integration tests for gating, back-editing, and two concurrent `submit()`
calls yielding one reference.

---

## P5 — REST API and error contract

**Goal:** the HTTP surface, with one error shape everywhere.

```text
Expose the REST API. Controllers stay thin — no business rules.

POST   /api/applications                        {email, country}
         201 → {applicationId, draftToken, resumeUrl, flow}
GET    /api/applications/{draftToken}
         200 → {status, country, email, lastCompletedStep, resumeStep, formData, flow}
PUT    /api/applications/{draftToken}/sections/{sectionId}
         200 → {lastCompletedStep, resumeStep, savedSection}
POST   /api/applications/{draftToken}/submit
         200 → {reference, status, submittedAt}
GET    /api/applications/reference/{reference}
         200 → submitted view (FR5)
GET    /api/flows/{country}                     200 → flow definition
GET    /api/dev/outbox                          dev profile only

resumeStep is computed from lastCompletedStep on the way out; it is never stored.

The draft token is the path identifier because it IS the credential (A8/T-3). Add a
comment recording that, and that this follows directly from authentication being out of
scope.

@RestControllerAdvice returning application/problem+json for every error:
{ "type", "title", "status", "requestId", "violations": [...] }
Validation failures from both layers use this same body. 404 for unknown token or
reference. 409 for writing to a submitted application.

Add a servlet filter that puts a requestId in the MDC and echoes it as an
X-Request-Id response header, honouring an inbound one if present.

Never log request or response bodies, email addresses, IBANs or draft tokens. Log
applicationId, reference and requestId only. Add @ToString exclusions on entity fields
holding personal data.
```

**Verify:** happy path with curl end to end; a bad IBAN returns problem+json with
`CHECKSUM_INVALID`; every response carries `X-Request-Id`.

---

## P6 — Decisioning and the notification port

**Goal:** the two seams where real systems will later plug in.

```text
Add mocked decisioning and the notification port.

interface DecisionEngine { Decision decide(OnboardingApplication a); }
Decision is a record: (identityConfidence, List<String> debtFlags, FinalDecision,
decidedAt) with FinalDecision in {APPROVE, REFER, DECLINE}.

MockDecisionEngine must be DETERMINISTIC — derive from stable input, never Random, so
tests are meaningful. Reasonable mocked market-specific signals:
 - identityConfidence lowered when the account holder name does not match the applicant
   name, or when the IBAN country differs from the registration country
 - a debt flag on a configured list of demo tax numbers
 - DECLINE on any hard flag, REFER on low confidence, otherwise APPROVE

The result is stored on the application. It is NEVER surfaced to the applicant and NEVER
changes status — status stays SUBMITTED (A1, FR6). In production this step is
asynchronous; here it runs synchronously behind the interface, which is the seam.

interface NotificationPort { void send(Notification n); }
OutboxNotificationAdapter writes to notification_outbox and sends nothing. Record the
resume link in the outbox, never in a log line — the token is a bearer credential and
logging it would contradict the logging rules in CLAUDE.md.

Add a comment noting that requiring the emailed link before any section may be written
turns it into email verification — the intended V2 behaviour (A20), reachable as one
guard with no schema change.

Unit-test the decision engine's branches. The brief asks specifically for tests around
flow transitions and decisioning.
```

**Verify:** `./gradlew test` green; `GET /api/dev/outbox` shows a resume link after
creating a draft.

---

## Consolidation — P4, P5 and part of P6 merged

Delivered as one prompt rather than three. The review loop between phases had become the
main consumer of budget, and P4's service, P5's API and P6's outbox share enough context
that reloading it three times was the expensive part, not the code.

The notification outbox arrived here rather than at P6 because `createDraft` writes the
RESUME_LINK row in the same transaction and `/api/dev/outbox` was already in the API
surface — the entity had to exist for either to work. Only the decision engine remained
of P6.

Committed as `P4-P6: application service, REST API, notification outbox`.

---

## PF — Frontend

Lettered rather than numbered because it was not in the original pack. Writing a
backend-only sequence was a mistake: the brief calls the form webapp *"the core of the
task — get this right first"*, and it very nearly became the thing that ran out of budget.

**Goal:** the applicant-facing webapp, rendering entirely from the flow definition.

```text
Build the frontend. This is what the brief calls the core of the task.

Three files, no build step, no framework, no npm:
  src/main/resources/static/index.html
  src/main/resources/static/app.js
  src/main/resources/static/styles.css

Served by Spring Boot at /. Plain ES modules and fetch. Dark theme, system font stack,
one accent colour, generous spacing — clarity over polish, per the brief.

THE CRITICAL RULE
Render every form field from the flow definition returned by the API. Never hardcode
DE/PL/NL forms in JavaScript. Adding a fourth country must require no frontend change.
If you find yourself writing `if (country === 'DE')` anywhere in app.js, stop — the
information you need belongs in the flow definition instead.

Render by field type:
  STRING  -> text input (respect maxLength)
  DATE    -> <input type="date">
  BOOLEAN -> yes/no select
  CONSENT -> checkbox with the field's label text
  a field carrying an options list -> <select>
If registryType or nationality have no options in the flow definition, add an `options`
component to FieldDefinition and populate it in the YAML. One component, not a new
mechanism.

requiredWhen: hide a dependent field until its condition is met, and reveal it when the
controlling field changes. The server remains the authority — this is convenience only.

Screens:
1. Landing — "Start a new application" (email + business registration country).
   If localStorage holds a draft token, also show "Continue on this device" with the
   email, country and resume step. Also a "Find a submitted application" link.
   Entering an email ALWAYS starts a new draft; never look one up by email.
2. Steps — one section per screen, in flow order, with "Step N of M" and a progress bar.
   Back and "Save and continue". PUT the section, and on 400 render violations[] inline
   against the named fields. A violation naming a field not on screen goes to a summary
   at the top so nothing is silently swallowed.
3. Review — read-only summary of every section, each with an "Edit" link back to that
   step. Mask the IBAN to its last four characters. "Submit application".
4. Success — show the reference prominently, and say the application cannot be edited.
5. Lookup — enter a reference, show the submitted application read-only (FR5).

State: keep the draft token in localStorage so "Continue on this device" works. Nothing
else is stored client-side. Copy for the save indicator is "Your progress is saved after
every step" — there is no autosave.

Do not add: a router library, a state library, a CSS framework, a component system, or
any bundler. One module per screen at most.

Verify by walking a full DE application end to end in the browser: create, fill all six
sections, resume mid-way by reloading, edit an earlier section from Review, submit, then
look the reference up. Report only what is broken.
```

**Verify:** the walkthrough above, plus `grep -E 'DE|PL|NL|SCHUFA|NIP|KvK' app.js`
returning nothing but the `iban` validator name used to decide IBAN masking.

Two backend additions fell out of this phase and were accepted: a `label` component on
`FieldDefinition` (deriving labels in JavaScript would have meant teaching it that NIP is
an acronym and that the bureau is SCHUFA in DE — exactly the country knowledge the
critical rule pushes into config), and `GET /api/flows` so the landing page's country
picker is also config-driven.

Committed as `PF: frontend webapp`.

---

## P7 — Test pass

**Goal:** cover what a reviewer will actually look for.

```text
Review test coverage and fill the gaps. Priorities, in order:

1. Concurrent submit — two threads calling submit() on one draft produce exactly one
   reference and one row. This is the most convincing test in the build; make it real
   concurrency, not two sequential calls.
2. Flow transitions — forward gating blocks skipping ahead; backward editing is allowed;
   last_completed_step never moves backward; writing to a SUBMITTED application is
   rejected.
3. Layer-2 validation — POST submit directly against a half-filled draft and confirm it
   is rejected with SECTION_MISSING per missing section.
4. Per-country validation — the same payload passes for one country and fails for
   another (postcode and tax id are the clearest cases).
5. Resume — create, save two sections, reload by token, confirm state and that
   resumeStep points at the third section.

Use Testcontainers with Postgres; JSONB and the partial unique index are Postgres-
specific and H2 would not exercise them.

Do not chase coverage percentage. Do not test getters or Spring wiring.
```

**Verify:** full suite green; deliberately break the conditional update in `submit()` and
confirm the concurrency test actually catches it.

---

## P8 — Documentation

**Goal:** the deliverable a reviewer reads first.

```text
Write README.md and ARCHITECTURE.md. ASSUMPTIONS.md already exists — do not rewrite it,
reference it.

README.md: what this is; how to run (docker compose up, and Gradle for local dev); how
to run tests; the full API surface with a curl walkthrough of the happy path; demo data
for all three countries with valid IBANs, NIPs, REGONs and KvK numbers; where to find
the resume link (the dev outbox endpoint) and why it is not emailed.

ARCHITECTURE.md, kept short and honest:
- module map and what each package owns
- the data model and why one table with a JSONB blob rather than a table per section
- how flows are configured, and the three shapes of country variation the config must
  handle — same fields with different rules, different fields entirely, and a different
  item set — since handling only the second is the shallow reading of this brief
- the seams: FlowDefinitionRepository, DecisionEngine, NotificationPort — and what
  drops into each one in production
- tradeoffs, cross-referenced to T-1..T-9 in ASSUMPTIONS.md
- what I would do next, ordered by what the MVP is meant to learn: funnel
  instrumentation first, then a real notification adapter, then email verification, then
  real integrations behind the existing seams

Prefer plain statements of tradeoffs over diagrams for their own sake. The brief asks
for clear tradeoffs, not diagram theatre.
```

**Verify:** follow your own README on a clean clone and confirm every command works.

---

## Notes on the prompting approach

Worth saying out loud in the submission, since these prompts are part of it:

| Choice | Why |
|---|---|
| Context written once into `CLAUDE.md` at P0 | Every later prompt inherits the contract instead of restating it, and the contract stays reviewable as a file. |
| Explicit "do not build" lists | With a scoped task the risk is not bad code, it is helpful scope creep — invented statuses, speculative abstractions. |
| The *reason* included with each rule | A rule with its rationale survives an agent's judgement call at 2am; a bare rule gets optimised away. |
| One prompt per step, verified between | Errors compound. A failed acceptance check localises the problem to one prompt. |
| Acceptance criteria stated up front | Turns "looks done" into "passes this check". |
| Prompt length is a cost, not a free variable | The biggest mistake here. Long prompts plus a long review reply per phase exhausted one session's context at P4 and forced a restart. `CLAUDE.md` made the restart cheap — the contract was on disk — but the right fix was shorter prompts and fewer merged phases from the start. |
| Deliberate-break verifications (P2, P7) | Confirms a test can actually fail. A concurrency test that passes against broken code is worse than no test. |
| Changes named, never referred to by list index | Two numbered lists were live at once and "items 2, 3 and 4" pointed at the wrong one. The agent resolved it by intent and flagged it — but the cheaper fix is not to create the ambiguity. |
| External test vectors, cited by source | A suite that only tests numbers the implementation itself generated proves internal consistency, not correctness. |
| Published *and* constructed vectors | They catch different things. Published REGON vectors never reached the remainder-10 mapping; removing it broke only the constructed boundary test. |
| Self-reported failures, including ones that left no trace | The agent corrupted a file to 1.7 MB with a `replace("", …)`, restored it from git, and reported the near-miss even though the commit showed nothing. `git diff --stat` after any scripted edit catches that class instantly. |
| Tests that assert their own premise | The REGON embedded-check test first asserts the outer checksum genuinely passes, so it cannot silently start failing for the wrong reason and stop testing anything. |
