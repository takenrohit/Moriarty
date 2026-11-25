---
title: Database connection pool exhaustion
service: checkout-api
symptoms:
- connection pool exhausted
- database connection timeout
- slow queries
tags:
- database
- postgres
- connection
- pool
---
# Database connection pool exhaustion

Use this runbook when checkout-api logs show connection pool timeouts or requests wait for a
database connection.

## Triage

1. Check the connection pool metrics: active, idle and pending connections, and acquisition time.
2. Search checkout-api logs for connection acquisition timeouts and note when they started.
3. Look at database metrics for long-running queries, lock waits and CPU saturation.
4. Check recent deployments for new queries, changed pool settings or removed indexes.

## Mitigation

5. Identify and cancel runaway queries with the database owner, with approval.
6. Raise the pool size only within the database connection limit, as an approved config change.
7. If a recent release introduced the slow query, propose a rollback; rollback needs on-call approval.

## Escalation

8. If the database itself is unhealthy, page the database on-call.
