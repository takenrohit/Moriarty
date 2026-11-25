---
title: Checkout API high error rate
service: checkout-api
symptoms:
- high 5xx error rate
- failed checkouts
- elevated http errors after deploy
tags:
- checkout
- errors
- deploy
- http
---
# Checkout API high error rate

Use this runbook when the checkout-api 5xx ratio is above its alert threshold.

## Triage

1. Open the checkout-api dashboard and find when the 5xx ratio started rising.
2. Search checkout-api logs for the most frequent exception types since that time.
3. Check traces to see whether errors come from checkout-api itself or from a downstream dependency such as payment-gateway.
4. List checkout-api deployments in the last 24 hours and compare their times with the start of the errors.

## Mitigation

5. If a downstream dependency is failing, follow that dependency's runbook instead of changing checkout-api.
6. If errors started within minutes of a deployment and the dependency is healthy, propose a rollback; rollback needs on-call approval.
7. If a feature flag introduced with the release is involved, disable the flag, with approval.

## Escalation

8. If the cause is unclear after 20 minutes, escalate to the checkout team lead.
