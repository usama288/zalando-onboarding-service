# Architecture notes

How this is put together and why. [ASSUMPTIONS.md](ASSUMPTIONS.md) holds the assumptions and
the trade-off register (A1–A21, T-1–T-10); this document cites those identifiers rather than
repeating them. [Bugs.md](Bugs.md) holds what is known and still open.

---

## Module map

One Spring Boot application. The boundaries below are package boundaries, not network ones —
each is a seam that could become a network hop later, and none of them needed to be one now.

| Package | Owns |
|---|---|
| `api` | Controllers, request and response records, the request-id servlet filter. Transport only: no rule about application state lives here |
| `api.error` | One `@RestControllerAdvice` turning every failure — ours and Spring MVC's — into RFC 7807 `application/problem+json` |
| `domain` | The `Application` entity, its repository, and `ApplicationService`. Every rule about what an application may do next |
| `flow` | Flow definitions loaded from YAML at startup, and resume derivation, which lives here because it walks a country's section order |
| `validation` | Layer 1 (field) and layer 2 (submission), plus the named validator beans the YAML references |
| `decision` | `DecisionEngine` and a deterministic mock, run after submission |
| `notification` | `NotificationPort` and the outbox adapter that records resume links |
| `support` | Clock, token generation, configuration properties |

The frontend is three files in `src/main/resources/static` — about 1,150 lines of HTML, CSS and
one ES module — served from the same jar by Spring's static resource handler. Same origin as
the API, so no CORS, no second deployment, no second pipeline. It renders every control from
the flow definition the API returns.

**Where the rules live, and why there.** `ApplicationService` owns sequencing — which section
may be written now, when progress moves, how a draft becomes a submission exactly once —
because there is no authentication anywhere in this system. A client can call any endpoint in
any order, so any rule enforced by the frontend is not enforced at all. Controllers read a
request, call one service method, and shape the result. The entity below refuses shapes that
cannot exist: no public constructor, no setters, and a submitted application that will not
mutate.

---

## Data model

One table, `applications`, plus `notification_outbox`. Lifecycle fields are typed columns;
all section content lives in a single `form_data` JSONB column, one key per completed section.

| Column | Why it exists |
|---|---|
| `id` (UUID) | Internal key. Never appears in a URL |
| `draft_token_hash` | SHA-256 of the resume token. The plaintext is returned once and never stored, so a database dump yields no working resume links |
| `reference` | The public name of a submitted application. Set only at submission |
| `applicant_email` | Trimmed and lower-cased. Unique **only across submitted rows** |
| `country` | Immutable — it selects the flow |
| `status` | `DRAFT` or `SUBMITTED`. There is no third value |
| `last_completed_step` | The furthest section actually completed; `NULL` on a fresh draft. Excludes `REVIEW` deliberately |
| `form_data` (JSONB) | Every completed section, each carrying its own `completedAt` |
| `schema_version` | How to read the blob after its shape changes |
| `decision` (JSONB) | The mocked decision, written after submission, returned by no endpoint |
| `row_version` | JPA `@Version`. Two tabs saving different sections write the same row |
| `created_at` / `updated_at` / `submitted_at` | `updated_at` is owned by Hibernate alone — no trigger, so there is only ever one clock writing it |

### Why one table with a blob, and not a table per section

A per-section table was the obvious alternative and was rejected (**T-10**).

The fields worth constraining, indexing and reasoning about — status, country, progress,
reference, email — are identical across all three countries. The fields that differ are exactly
the ones nothing queries. Modelling those relationally means either a sparse table with columns
no country fills, or an `application_sections` table that turns retrieval by reference into a
join for no gain. One entity and one repository fits what this actually is.

What the blob costs, accepted honestly:

- It is not queryable with the schema's help. Analytics across applicants' field values would
  need either JSONB indexes or a projection.
- It needs `schema_version` to stay readable once its shape changes. That column exists because
  of this decision, and for no other reason. It is **not** flow versioning, which was considered
  and rejected as speculative (**A21**).
- Two tabs saving different sections contend on one row. A per-section table would have given
  conflict-freedom for free; optimistic locking buys it back, and the loser gets a 409 it can
  safely retry because nothing was written.

Per-section `completedAt` stamps are in the blob because drop-off per step and time-to-complete
are the questions this MVP exists to answer, and without them the data cannot answer them.

### Three identifiers, three jobs

Conflating any two of these would be a real mistake, so they are separate values with separate
lifetimes: the internal `id`, which nobody outside the service sees; the **draft token**, a
256-bit resume credential stored only as a hash; and the **reference**, 80 bits over a Crockford
base32 alphabet with I, L, O and U removed so it survives being read down a phone. A draft needs
a private way back long before a public name for the application exists.

### The one transition

```text
DRAFT ──submit──▶ SUBMITTED
```

Enforced three times over, on purpose: the service refuses writes to a non-draft, the entity
throws if asked to mutate, and a `CHECK` constraint makes a half-submitted row unrepresentable.
The transition itself is one conditional statement —

```sql
UPDATE applications SET status='SUBMITTED', reference=?, submitted_at=?
 WHERE id=? AND status='DRAFT'
```

— so two simultaneous submits resolve to one winner and one reader. One row updated means this
caller performed it; zero means somebody else did, and the caller re-reads and returns the
existing reference. The reference is generated *inside* the transition, never before it, so a
losing caller never mints one (**A10**).

Email uniqueness is a partial index over submitted rows only. Unfiltered, it would let anyone
lock a person out of onboarding by starting a draft with their address (**A7**).

---

## How flows are configured

Each country ships a YAML file declaring its sections in order, and for each field: type, label,
options, length, pattern, conditional visibility, and the **names** of the validators that apply.
Those names resolve to Java beans. Both halves are checked at startup — unknown YAML keys are
rejected, a validator name with no bean stops the application, and two beans claiming one name
stop it too. A mistyped rule fails loudly instead of silently never applying.

### The three shapes of country variation

The brief's shallow reading is that countries differ in *which fields exist*. That is only one
of three shapes, and configuration that handles just that one would not survive the other two.

**1. Same fields, different rules.** Postcode is `^\d{5}$` in Germany, `^\d{2}-\d{3}$` in Poland,
`^\d{4} ?[A-Za-z]{2}$` in the Netherlands. Tax identifiers differ the same way, with different
checksum algorithms behind the same declaration mechanism.

**2. Different fields entirely.** The registry section: Handelsregister, gated on a yes/no
because a German Kleingewerbe has no entry at all; CEIDG, unconditional, because CEIDG
registration is how a Polish sole proprietorship exists; KvK number, trade name and SBI code.
Not three variants of one shape — three different sections that happen to occupy the same step.

**3. A different item set.** Which consents a country requires. This one is proven by
`FlowVariationCapabilityTest`, a **test fixture**, not by shipped configuration — because all
three real markets require the same five consents, differing only in which bureau the
credit-check text names. Inventing a fourth consent for Poland would demonstrate the capability
by putting fiction into configuration a reviewer might believe. The test asserts the engine
supports genuinely different item sets; the YAML stays true.

Country rules are never `if (country == DE)` branches and never database constraints. The
database constrains lifecycle only — status values, the draft/submitted shape, email uniqueness
— so adding a market needs no migration (**T-6**).

### The frontend follows the same rule

`app.js` contains no country code, no country name, and no field name other than the `iban`
*validator* name it uses to decide masking. Labels and dropdown options come from the flow
definition, which is why `label` and `options` are components of `FieldDefinition`: deriving a
label in JavaScript would mean knowing that NIP and KvK are acronyms and that the bureau is
SCHUFA in Germany and BKR in the Netherlands. That is country knowledge, and it belongs in
configuration.

Frontend validation is convenience. It hides a field whose condition is not met and puts errors
beside the right input, but every rule is re-applied server-side, because browser checks can be
bypassed and here there is not even a login to bypass first.

---

## The seams

Three interfaces exist because three things are mocked. Each is the point where the real thing
drops in.

| Seam | Today | In production |
|---|---|---|
| `FlowDefinitionRepository` | Loads `flows/{DE,PL,NL}.yml` once at startup into an immutable map | Definitions move to a database or a configuration service. Callers depend on the interface and never on the loader, so nothing else changes. Runtime-editable flows are also the point at which pinning a flow version per application stops being speculative (**A21**) |
| `DecisionEngine` | A deterministic mock scoring name agreement, IBAN country and a demo debt register | A real identity or bureau adapter. It runs synchronously at submit **only because a queue is not affordable in this timebox** (**T-1**); moving it to an event consumed by a worker changes `ApplicationService.submit` and nothing else |
| `NotificationPort` | An adapter that records the resume link in an outbox table, in the same transaction as the draft | An email provider. The port and a recording adapter cost about half an hour and turn a missing feature into a one-line adapter swap (**T-7**). The outbox rather than a log line is deliberate: the link is a bearer credential |

The mock decision engine is deterministic — derived from stored application data, the flow
definition and configured demo values, never `Random`. A decision that changes between runs
cannot be tested and cannot be explained to the applicant it was made about.

---

## Practice note: migrations

`V1__init.sql` has been edited repeatedly during this build. That is correct **only** because
every run here starts from a destroyed volume.

Once a real environment has applied a migration, changes go in a new migration, never an edit
to an applied one. An edited migration means the checksum no longer matches what was applied,
and Flyway is right to refuse to start — but the deeper problem is that two environments now
disagree about what the schema is, with no record of how. V1 stayed editable here so the build
did not accumulate a V2 that exists only to add a column comment; the first deployment ends
that.

---

## Trade-offs

The register lives in [ASSUMPTIONS.md](ASSUMPTIONS.md) §7. The ones that shaped the code most:

- **T-1** — mocked decisioning, synchronous, behind an interface. A queue is the production
  answer and costs infrastructure the timebox cannot afford. The interface is the seam.
- **T-2** — only completed steps are persisted. No autosave, no partial-section state, no
  draft-conflict handling. Cost: input on the step being typed is lost if the tab closes.
- **T-3** — unguessable tokens as bearer credentials, because authentication is excluded. The
  security property rests entirely on entropy. Tokens therefore appear in URLs, which is
  inherent to the design, and is why they are kept out of logs and out of error bodies.
- **T-4** — email uniqueness enforced at submit. Gives a clean rejection and accepts an
  enumeration oracle, which A19 then avoids by never looking an email up on screen.
- **T-5** — country immutable after creation. Cascade-invalidating downstream sections is not
  affordable, and "start a new application" is an honest V1 answer.
- **T-6** — country differences as configuration. A fourth country should be a configuration
  change, not a code change.
- **T-7** — notification port built, integration stubbed to an outbox.
- **T-9** — flow definitions unversioned. Speculative at this size; the limitation is stated.
- **T-10** — one table, one JSONB column, discussed above.

Two smaller decisions worth naming, since a reviewer will see them:

**Vanilla JavaScript, no framework.** The flow is linear and the shared client state is one
token plus the server's own `resumeStep`. A framework would add a build pipeline and a
dependency surface to keep patched, for a page whose hardest problem is rendering the field the
API described — and the fields come from configuration anyway, so a component library would
mostly wrap a `switch` on field type. This stops being the right call when the workflow becomes
non-linear, when a design system needs sharing with other applications, or when more than one or
two people work on the frontend at once.

**Static assets are served `no-cache`.** `index.html`, `app.js` and `styles.css` carry no hash
in their filenames, so `no-cache` — keep it, but always revalidate — costs one conditional
request per load and means nobody is stuck on a stale `app.js` after a deploy.

---

## What I would do next

Ordered by what this MVP is meant to learn, not by what is most interesting to build.

1. **Funnel instrumentation.** Drop-off per step, time-to-complete, abandonment point. This is
   the question the MVP exists to answer, the per-section `completedAt` stamps are already
   there for it, and it should come before anything that adds features to measure.
2. **A real notification adapter** behind `NotificationPort`. Small, and it unlocks (3).
3. **Email verification.** The resume link and a verification link are the same artifact; the
   difference is only whether the form is gated on clicking it. That is one guard on section
   writes — no new subsystem, no schema change (**A20**).
4. **Real registry, identity and banking integrations**, one country at a time, behind the seams
   that already exist. Asynchronous decisioning and a review queue follow from this rather than
   preceding it.

After that, and only if the data argues for it: encrypted storage for the most sensitive fields,
retention enforcement, an audit log, metrics and tracing, accessibility and localisation. Not
microservices — nothing here is constrained by being one deployable.
