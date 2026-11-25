---
title: Payment gateway degradation
service: payment-gateway
symptoms:
- payment provider timeout
- upstream timeouts from payment-provider
- high p99 latency
- elevated 5xx errors on checkout
tags:
- payments
- latency
- timeout
- upstream
- provider
---
# Payment gateway degradation

Use this runbook when payment-gateway latency rises or calls to the external payment provider
time out, usually seen as 5xx errors or slow responses on checkout-api.

## Triage

1. Open the payment-gateway dashboard and compare p99 latency and error rate with the previous hour.
2. Check the payment provider status page and the provider incident channel for an announced outage.
3. Look at payment-gateway traces for failing `payment-gateway -> payment-provider` spans and note the error type.
4. Check whether payment-gateway or checkout-api was deployed in the last 24 hours, and review timeout or retry changes.

## Mitigation

5. If the provider is degraded, enable the secondary provider route through the payment routing flag, with on-call approval.
6. If the gateway connection pool is saturated, confirm pool metrics before changing pool size; config changes need approval.
7. Do not restart payment-gateway or roll back checkout-api without on-call approval and evidence that the change caused the errors.

## Escalation

8. If errors continue for more than 15 minutes, page the payments team and open a ticket with the provider.
9. Post a status update in the incident channel every 30 minutes until latency is back to baseline.
