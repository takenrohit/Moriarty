-- Re-investigation (plan 3.9, Req 9.4, CP-80). A re-investigation stores its report as the next version
-- with kind 'stored'; a report produced after one was already stored for the same investigation stays a
-- distinct audit row with kind 'duplicate' (Req 12.7). ReportRepository.find reads the highest 'stored'
-- version, so a duplicate never replaces the current report. Existing rows above version 1 were written
-- by JdbcAuditService.recordReport and are duplicates.

ALTER TABLE reports ADD COLUMN kind text NOT NULL DEFAULT 'stored';
UPDATE reports SET kind = 'duplicate' WHERE version > 1;
