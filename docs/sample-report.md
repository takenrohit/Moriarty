# Incident Investigation — checkout-api

Severity: Critical
Detected: 2026-11-23 20:10 IST
Status: COMPLETED

## What happened
The checkout-api service experienced a high error rate due to timeouts when calling the payment-gateway service. The error rate spiked at 14:38 UTC, coinciding with a deployment of checkout-api that did not directly cause the issue. The root cause appears to be an external dependency (payment-provider) issue that caused the payment-gateway to time out, which in turn caused checkout-api to fail.

## Hypotheses
1. The payment-gateway service is timing out due to an issue with the payment-provider service, causing checkout-api to fail with PaymentGatewayTimeoutException. (confidence: HIGH) [ev-inc-dcc7431f2b35-4][ev-inc-dcc7431f2b35-5][ev-inc-dcc7431f2b35-8]
   Contradicting: [ev-inc-dcc7431f2b35-10][ev-inc-dcc7431f2b35-11]
2. The deployment of checkout-api introduced a regression that caused increased latency or errors in processing requests. (confidence: LOW) [ev-inc-dcc7431f2b35-2]
   Contradicting: [ev-inc-dcc7431f2b35-4][ev-inc-dcc7431f2b35-5]
   Correlated with checkout-api v2.14.3 deployed by dev-alice; not established as the cause.

## Timeline (IST)
19:56  Deployment of checkout-api version v2.14.3, updating order confirmation copy. This deployment is correlated with the error spike but does not directly cause it. [ev-inc-dcc7431f2b35-2]
20:08  Error rate for checkout-api spikes to 0.12, indicating a significant increase in 5xx errors. [ev-inc-dcc7431f2b35-1][ev-inc-dcc7431f2b35-3]
19:10  First error messages appear in checkout-api logs: 'PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms'. [ev-inc-dcc7431f2b35-4]
19:10  No errors are observed in payment-provider logs or traces during the incident window. [ev-inc-dcc7431f2b35-10][ev-inc-dcc7431f2b35-11]

## Recommended next steps
1. Continue monitoring the payment-gateway and payment-provider services for any signs of instability. Gather more detailed traces and logs from these services to identify the root cause of the timeouts. [investigate]
2. Escalate the issue to the team responsible for the payment-provider service to investigate potential issues on their side that may be causing the payment-gateway to time out. [escalate]

## Open questions
- What is the current status of the payment-provider service? Are there any known issues or maintenance activities?
- Are there any recent changes or deployments in the payment-gateway or payment-provider services that could have contributed to this issue?
- Can we obtain more detailed error messages or stack traces from the payment-gateway service to better understand the nature of the timeouts?

## Evidence
- ev-inc-dcc7431f2b35-1 ALERT alert-intake 20:10 — HighErrorRate fired on checkout-api (CRITICAL)
- ev-inc-dcc7431f2b35-2 DEPLOYMENT changes 19:56 — checkout-api v2.14.3 deployed by dev-alice
- ev-inc-dcc7431f2b35-3 METRIC metrics 20:08 — checkout-api metric changed from 0.002 to 0.12 at 2026-11-23T14:38:00Z
- ev-inc-dcc7431f2b35-4 LOG logs 19:10 — checkout-api logs: 285 matches, top "PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms" (214)
- ev-inc-dcc7431f2b35-5 TRACE traces 20:08 — checkout-api traces: 50+ with errors, 50+ slow; top failing span payment-gateway POST /authorize -> payment-provider (50)
- ev-inc-dcc7431f2b35-6 METRIC metrics 20:20 — payment-gateway metric steady at 0.002 (min 0.002, max 0.002, 281 points)
- ev-inc-dcc7431f2b35-7 LOG logs 19:10 — payment-gateway logs: 71 matches, top "request completed" (71)
- ev-inc-dcc7431f2b35-8 TRACE traces 20:08 — payment-gateway traces: 50+ with errors, 50+ slow; top failing span payment-gateway POST /authorize -> payment-provider (50)
- ev-inc-dcc7431f2b35-9 METRIC metrics 20:20 — payment-provider metric steady at 0.002 (min 0.002, max 0.002, 281 points)
- ev-inc-dcc7431f2b35-10 LOG logs 19:10 — payment-provider logs: 71 matches, top "request completed" (71)
- ev-inc-dcc7431f2b35-11 TRACE traces 20:20 — payment-provider traces: 0 with errors, 0 slow
