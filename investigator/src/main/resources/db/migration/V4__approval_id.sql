-- Slack approval workflow (plan 3.7, Req 7.1, 7.4, CP-81). approvals stays insert-only for the
-- application role (V2__audit.sql): a request is a 'PENDING' row and its decision a second row with the
-- same approval_id ('APPROVED' or 'REJECTED'); the latest row is the current state. The partial unique
-- indexes allow one request and one decision per approval, so a second click records nothing.
-- Rows written before this migration have no approval_id.

ALTER TABLE approvals ADD COLUMN approval_id text;
CREATE UNIQUE INDEX approvals_request_uq ON approvals (approval_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX approvals_decision_uq ON approvals (approval_id) WHERE status <> 'PENDING';
