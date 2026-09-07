-- Onboarding MVP, initial schema.
--
-- One table holds an application. Every section the applicant completes is merged into
-- the single form_data blob (see T-10 in ASSUMPTIONS.md); there is no per-section table.

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

COMMENT ON COLUMN applications.draft_token_hash IS
  'SHA-256 of the resume token, never the token itself. The plaintext is returned to the '
  'caller once, at creation, so a database dump yields no working resume links.';

COMMENT ON COLUMN applications.last_completed_step IS
  'Furthest section actually completed; NULL on a fresh draft. Holds real sections only. '
  'REVIEW is deliberately absent: it is a screen that persists nothing, and including it '
  'would make this column track viewing position rather than progress. Where to resume is '
  'derived from this value on the way out, never stored.';

COMMENT ON COLUMN applications.schema_version IS
  'How to interpret form_data after its shape changes. This is not flow versioning, which '
  'was deliberately rejected (A21).';

COMMENT ON COLUMN applications.updated_at IS
  'Owned by Hibernate @UpdateTimestamp on the Application entity, and by nothing else. '
  'Do NOT add a trigger: two writers on one column means two clocks that can disagree. '
  'DEFAULT NOW() fires on INSERT only -- it is a floor for out-of-band writes, not an '
  'owner, and it will not fire on UPDATE.';

COMMENT ON COLUMN applications.row_version IS
  'JPA @Version. form_data is a single blob, so two tabs saving different sections write '
  'the same row; optimistic locking is the accepted cost of one-table storage (T-10).';

COMMENT ON CONSTRAINT application_submission_state ON applications IS
  'A draft has neither reference nor submitted_at; a submitted application has both. '
  'Keeps half-submitted rows unrepresentable.';

-- Partial, and deliberately so. A plain UNIQUE (applicant_email) would also block a second
-- DRAFT, which would let anyone lock a person out of onboarding simply by starting a draft
-- with their address. Scoping the rule to submitted rows makes it read "one submitted
-- application per email" rather than "one draft in existence per email". Duplicate drafts
-- are permitted and harmless.
CREATE UNIQUE INDEX ux_applications_email_submitted
  ON applications (applicant_email) WHERE status = 'SUBMITTED';

COMMENT ON INDEX ux_applications_email_submitted IS
  'Uniqueness applies to SUBMITTED applications only. An unfiltered UNIQUE (applicant_email) '
  'would let a stranger block an email address from onboarding by starting a draft with it.';

CREATE TABLE notification_outbox (
  id UUID PRIMARY KEY,
  application_id UUID NOT NULL REFERENCES applications(id),
  type VARCHAR(40) NOT NULL,
  recipient VARCHAR(320) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE notification_outbox IS
  'Resume links are recorded here and never sent (A18). The outbox rather than a log line '
  'is deliberate: the resume token is a bearer credential and logging it would contradict A13.';
