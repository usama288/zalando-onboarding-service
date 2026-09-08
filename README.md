# Onboarding service

Self-service onboarding for solo entrepreneurs in **Germany, Poland and the Netherlands**. An
applicant enters an email address and a country, completes six form steps, can leave and
resume, and submits to receive an application reference they can look up later.

One Spring Boot application: REST API, a vanilla-JavaScript frontend served from the same jar,
PostgreSQL. No accounts and no login — a constraint of the assignment, not an omission.

Java 21 · Spring Boot 3.5.16 · Gradle wrapper · PostgreSQL 16 · Flyway · no frontend build step.

Each country's form is declared in YAML and enforced by named Java validator beans. There is
no `if (country == DE)` branch in the Java or the JavaScript; adding a fourth market is a
configuration change.

**Companion documents.** [ARCHITECTURE.md](ARCHITECTURE.md) — module map, data model, seams and
trade-offs. [ASSUMPTIONS.md](ASSUMPTIONS.md) — every assumption, with the A- and T- identifiers
the code comments cite. [Bugs.md](Bugs.md) — what is known and still open.

---

## Run it

### Docker — everything

```bash
docker compose up --build
```

Open <http://localhost:8080/>. Ports 8080 and 5432. Nothing but Docker is needed; the JDK and
Gradle run inside the build image. The first build takes a few minutes, later ones seconds.

Ready when `curl -sf http://localhost:8080/api/flows` returns 200. There is no Actuator
dependency, so there is no `/actuator/health`.

Flyway applies `V1__init.sql` at startup; no manual database step. Stop with `Ctrl-C`, then
`docker compose down`. Adding `-v` deletes the volume — **that erases every application in it**.

The Compose stack runs with `SPRING_PROFILES_ACTIVE=dev`, which enables the outbox endpoint
described below. Do not set that profile in a deployed environment: it has no authentication in
front of it and its payloads are bearer credentials.

### Gradle — local development

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

Needs Docker (for PostgreSQL) and **JDK 21**. Gradle comes from the wrapper. There is no Maven
wrapper — every command is `./gradlew`.

Stop with `Ctrl-C`, then `docker compose stop postgres`.

### Tests

```bash
./gradlew test
```

**188 tests, 0 failures**, about 12 seconds from clean. Gradle prints `UP-TO-DATE` and skips the
suite when nothing has changed — use `./gradlew clean test` to force a run. One class:

```bash
./gradlew test --tests 'com.zalando.onboarding.api.OnboardingApiTest'
```

**Docker is required**: 44 tests boot a Spring context against a real PostgreSQL 16 container
via Testcontainers. The other 144 are plain JUnit — no Docker, no Spring, no database. There is
no H2, so the JSONB mapping and `ddl-auto: validate` are genuinely exercised. When the suite
fails locally the cause is almost always the Docker daemon not running, or a JDK that is not 21.

---

## About the tests

**188 test cases from 152 test methods** — 139 plain `@Test`, plus 13 `@ParameterizedTest`
methods contributing 49 data rows (checksum vectors, date formats, per-country repeats).

| Area | Cases |
|---|---:|
| Validators — both layers, checksums, per-country rules | 99 |
| API — HTTP behaviour, gating, submission, error contract | 37 |
| Flow configuration — YAML loading, resume derivation, variation | 34 |
| Decisioning | 11 |
| Service and transitions — timestamps, token entropy | 7 |

The service's transition rules are deliberately tested *through* the API rather than against the
service directly. With no authentication a client can call any endpoint in any order, so those
rules only mean something when asserted over HTTP.

**The eight that carry the design.** These are the ones that would catch a real regression:

| Test | What breaks if it fails |
|---|---|
| `OnboardingApiTest.twoSimultaneousSubmitsMintOneReference` | Two real threads race the conditional `UPDATE`. A double-click mints two references. This is the only test that reaches the zero-rows branch — a sequential double submit short-circuits before it |
| `OnboardingApiTest.forwardGatingBlocksSkippingAStep` | A client `PUT`s straight at step 3 and the server takes it |
| `OnboardingApiTest.lastCompletedStepNeverMovesBackward` | Correcting step 1 drags progress back to step 1. Asserted against the row, not the response |
| `OnboardingApiTest.submittingAHalfFilledDraftReportsSectionMissingPerMissingSection` | Layer 2 stops being what makes submission safe without auth |
| `SectionValidatorTest.aTaxPayloadValidInOneCountryIsRefusedInAnother` | Country variation quietly stops varying — the same payload must pass DE and fail PL |
| `ResumeDerivationTest.derivationWalksTheCountryFlowNotTheEnumOrder` | Resume follows the enum's declaration order instead of the country's flow. The fixture omits `ADDRESS` on purpose, so a flow that skips a section still resumes correctly |
| `SectionValidatorTest.aRequiredConsentSentAsFalseIsRefusedExactlyLikeAnAbsentOne` | A refused consent counts as an answer, and an application submits with terms declined |
| `MockDecisionEngineTest` — APPROVE, REFER, DECLINE | Decisioning stops being deterministic, at which point every other assertion about it is meaningless |

---

## API

No auth headers. **The draft token is a path segment because it is the credential.** Errors are
RFC 7807 `application/problem+json`; every response carries `X-Request-Id`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/applications` | Start a draft from `{email, country}` → `201` with `draftToken`, `resumeUrl`, `flow` |
| `GET` | `/api/applications/{draftToken}` | Load it — status, progress, `resumeStep`, `formData`, `flow`, and `reference` once submitted |
| `PUT` | `/api/applications/{draftToken}/sections/{sectionId}` | Save one completed section |
| `POST` | `/api/applications/{draftToken}/submit` | Submit. Idempotent |
| `GET` | `/api/applications/reference/{reference}` | Read a submitted application |
| `GET` | `/api/flows` | Every supported country's flow definition |
| `GET` | `/api/flows/{country}` | One country's, case-insensitive |
| `GET` | `/api/dev/outbox` | Recorded resume links. **`dev` profile only** |

Failures: `400` for a bad payload (field violations, `SECTION_UNKNOWN`, `COUNTRY_UNKNOWN`, and
`SECTION_MISSING` on submit); `409` when the application's state forbids the call
(`SECTION_NOT_REACHABLE`, `APPLICATION_ALREADY_SUBMITTED`, `EMAIL_ALREADY_SUBMITTED`,
`CONCURRENT_MODIFICATION`); `404` for an unknown token or reference.

### Happy path with curl

```bash
# 1. Start a German application
TOKEN=$(curl -s -X POST localhost:8080/api/applications \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo.user@example.com","country":"DE"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["draftToken"])')

# 2. Six sections, in order. Skipping ahead returns 409.
S="localhost:8080/api/applications/$TOKEN/sections"
curl -s -X PUT $S/personalDetails -H 'Content-Type: application/json' \
  -d '{"firstName":"Ada","lastName":"Lovelace","dateOfBirth":"1990-05-17","nationality":"DE"}'
curl -s -X PUT $S/address -H 'Content-Type: application/json' \
  -d '{"street":"Hauptstrasse","houseNumber":"12a","postalCode":"10115","city":"Berlin"}'
curl -s -X PUT $S/taxInformation -H 'Content-Type: application/json' \
  -d '{"taxNumber":"12345678901","vatRegistered":false}'
curl -s -X PUT $S/businessRegistry -H 'Content-Type: application/json' \
  -d '{"registered":false}'
curl -s -X PUT $S/paymentDetails -H 'Content-Type: application/json' \
  -d '{"accountHolder":"Ada Lovelace","iban":"DE68 2105 0170 0012 3456 78"}'
curl -s -X PUT $S/consent -H 'Content-Type: application/json' \
  -d '{"informationConfirmed":true,"termsOfService":true,"dataProcessing":true,"privacyNotice":true,"creditCheck":true}'

# 3. Submit, then submit again — the same reference comes back both times
REF=$(curl -s -X POST localhost:8080/api/applications/$TOKEN/submit \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["reference"])')
curl -s -X POST localhost:8080/api/applications/$TOKEN/submit

# 4. Look it up
curl -s localhost:8080/api/applications/reference/$REF
```

Consents may be sent as `true`. The server writes the stored record itself — the version comes
from the flow definition and the timestamp from the server clock — so a client cannot assert
which text was agreed to, or when.

### Error shape

```json
{
  "type": "https://onboarding.zalando.com/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "instance": "urn:uuid:91125645-8bea-4d82-8d16-ca373e04ebf8",
  "requestId": "91125645-8bea-4d82-8d16-ca373e04ebf8",
  "violations": [
    { "field": "firstName",   "section": "personalDetails", "code": "REQUIRED",  "message": "This field is required" },
    { "field": "dateOfBirth", "section": "personalDetails", "code": "NOT_ADULT", "message": "You must be at least 18 years old" }
  ]
}
```

`instance` is the request id as a URN, matching the `X-Request-Id` header — never the URI that
was called, which would carry the draft token. Clients branch on `code`, never on `message`.

---

## Demo data

Fabricated or published test vectors. The identifiers carry real checksums, so they must be
exact — invented numbers are rejected, which is the point.

| | Germany | Poland | Netherlands |
|---|---|---|---|
| Postal code | `10115` | `00-950` | `1012 AB` |
| Tax | Steuernummer `12345678901`, VAT **No** | NIP `856-734-62-15` (or `8567346215`) | VAT **No** |
| VAT id, if VAT = **Yes** | `DE136695976` | any text | any text |
| Registry | Handelsregister **No**. If **Yes**: type `HRA`, number `HRA 12345`, court `Amtsgericht Charlottenburg` | REGON `192598184`, CEIDG date any past date, PKD `62.01.Z` | KvK `12345678`, trade name any, SBI `6201` |
| IBAN | `DE68 2105 0170 0012 3456 78` | `PL61 1090 1014 0000 0712 1981 2874` | `NL91 ABNA 0417 1643 00` |
| Date of birth | any date at least 18 years ago | | |

Any of those IBANs is accepted in any country — SEPA makes a cross-border account normal, so it
is not rejected (A12). It does lower the mocked decision's identity confidence.

Tick **all five** consents; the form omits unchecked ones, so the server rejects with `REQUIRED`.

**Worth trying.** Enter `00-950` as a German postcode: rejected inline, accepted in a Polish
application. Enter the NIP with hyphens: accepted, and Review shows `8567346215`, the stored
form. Edit the URL to `#/apply/consent` from step 2: bounced back with an explanation.

## Where the resume link is

Nothing is emailed. A `NotificationPort` and an outbox adapter exist; there is no provider,
because notifications are out of scope for the assignment (A18). The link is written down
rather than sent, and to the outbox rather than a log line, because it contains a bearer
credential.

```bash
curl -s localhost:8080/api/dev/outbox | python3 -m json.tool | grep resumeUrl
```

Open that URL in a private window: it stores the token in that browser and strips it from the
address bar. The frontend also shows the link on screen once, immediately after an application
is created — with no delivery, that is the only route back if the browser forgets.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Port 8080 was already in use` | `lsof -ti:8080 \| xargs kill`, or set `SERVER_PORT` |
| `Bind for 0.0.0.0:5432 failed` | A local PostgreSQL is running — stop it, or change the published port |
| `Cannot connect to the Docker daemon` | Start Docker. Needed for both run paths and for 44 tests |
| `Unsupported class file major version` | Gradle needs JDK 21 |
| `Could not find a valid Docker environment` in tests | Same cause. The 144 non-Docker tests still pass |
| First test run is slow | Testcontainers is pulling `postgres:16-alpine` |
| `Schema-validation:` at startup | The local database predates a schema change. `docker compose down -v` **deletes all local data** |
| `./gradlew test` prints `UP-TO-DATE` | Use `./gradlew clean test` |
