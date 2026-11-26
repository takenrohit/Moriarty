-- Baseline schema (architecture.md section 7, Req 12.1).
--
-- Domain tables (incidents, investigations, evidence, reports) keep the section 7 key columns for
-- queries, plus a "record" column with the exact JSON_Codec serialization of the domain record, which
-- the repositories read back (Req 12.8). "record" is json, not jsonb: jsonb rejects the \u0000 escape and
-- normalizes numbers (-0.0, 1.7976931348623157E308), so it cannot round-trip every value. The jsonb
-- columns are query copies; in them and in text columns a NUL character is stored as U+FFFD, because
-- PostgreSQL text cannot hold NUL. Timestamps are timestamptz copies (microsecond precision).
--
-- Audit tables (alerts_raw, tool_calls, llm_exchanges, reports, approvals) are insert-only for the
-- application role (V2__audit.sql, Req 12.6). There are no foreign keys, matching the in-memory
-- repositories and keeping audit inserts independent of the domain rows.

CREATE TABLE incidents (
    id           text PRIMARY KEY,
    fingerprint  text,
    service      text,
    severity     text,
    alert_name   text,
    labels       jsonb NOT NULL,
    detected_at  timestamptz,
    status       text NOT NULL,
    record       json NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incidents_fingerprint_status_idx ON incidents (fingerprint, status);

CREATE TABLE alerts_raw (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id  text NOT NULL,
    source       text NOT NULL,
    payload      jsonb,
    incident     jsonb,
    received_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX alerts_raw_incident_idx ON alerts_raw (incident_id);

CREATE TABLE investigations (
    incident_id      text PRIMARY KEY,
    status           text NOT NULL,
    window_start     timestamptz,
    window_end       timestamptz,
    tool_calls_used  integer NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    record           json NOT NULL
);

CREATE TABLE evidence (
    seq          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id           text NOT NULL UNIQUE,
    incident_id  text NOT NULL,
    type         text,
    source       text,
    observed_at  timestamptz,
    summary      text,
    headline     text,
    payload      jsonb NOT NULL,
    citation     text,
    redactions   jsonb NOT NULL,
    record       json NOT NULL
);
CREATE INDEX evidence_incident_idx ON evidence (incident_id, seq);

CREATE TABLE tool_calls (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id  text,
    tool_name    text NOT NULL,
    args         jsonb,
    status       text NOT NULL,
    duration_ms  bigint NOT NULL,
    evidence_id  text,
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tool_calls_incident_idx ON tool_calls (incident_id);

CREATE TABLE llm_exchanges (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id    text,
    model          text,
    request        jsonb,
    response       jsonb,
    input_tokens   integer,
    output_tokens  integer,
    latency_ms     bigint NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX llm_exchanges_incident_idx ON llm_exchanges (incident_id);

-- Version 1 is the stored Investigation_Report (ReportRepository.saveIfAbsent); later versions are
-- reports produced after one was already stored, kept as distinct audit rows (Req 12.7).
-- validation_status is reserved for Phase 3 and NULL in Phase 2.
CREATE TABLE reports (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id        text NOT NULL,
    version            integer NOT NULL,
    status             text,
    body               jsonb NOT NULL,
    validation_status  text,
    record             json NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, version)
);

CREATE TABLE approvals (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id   text NOT NULL,
    action        text NOT NULL,
    status        text NOT NULL,
    requested_at  timestamptz,
    decided_by    text,
    decided_at    timestamptz
);
CREATE INDEX approvals_incident_idx ON approvals (incident_id);

-- Created for the schema; not written in Phase 2 (FR-52 is Phase 3).
CREATE TABLE feedback (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id   bigint NOT NULL,
    rating      integer,
    note        text,
    user_name   text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
