-- Application role and append-only audit grants (Req 12.6, CP-49, CP-57).
--
-- Flyway runs as the schema owner. The application connects as the separate, non-owner login role
-- ${app_role} (spring.datasource.username), which has no UPDATE, DELETE or TRUNCATE on the audit
-- tables. A superuser or the table owner would bypass these grants, so the application must never
-- connect as the Flyway user. The role is created here only if it does not exist yet, with the
-- password from spring.datasource.password; a pre-provisioned role is left unchanged.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_role}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', '${app_role}', '${app_password}');
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO "${app_role}";

-- Mutable domain tables.
GRANT SELECT, INSERT, UPDATE ON incidents, investigations, evidence, feedback TO "${app_role}";

-- Audit tables: insert and read only.
GRANT SELECT, INSERT ON alerts_raw, tool_calls, llm_exchanges, reports, approvals TO "${app_role}";
REVOKE UPDATE, DELETE, TRUNCATE ON alerts_raw, tool_calls, llm_exchanges, reports, approvals
    FROM "${app_role}";

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO "${app_role}";
