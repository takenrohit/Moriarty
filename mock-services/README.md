# Mock services and demo deploy

One Spring Boot app runs three times in the Compose stack with a different `APP_ROLE` (CP-56):
`checkout-api` → `payment-gateway` → `payment-provider`. `checkout-api` generates its own baseline
traffic (CP-58). `deploy.ps1` / `deploy.sh` are the Deploy_Script: they record a deployment in the
Fake GitHub and bump the service's version label.

Build and test: `.\mvnw.cmd -q verify` in this folder (`./mvnw -q verify` on Linux/macOS).

## Reproduce the demo scenario

Run from the repository root. PowerShell first, bash below each step.

1. Create the investigator's local env file once and set `LLM_API_KEY` in it (the file is git-ignored):

   ```powershell
   Copy-Item investigator/.env.example investigator/.env
   ```
   ```bash
   cp investigator/.env.example investigator/.env
   ```

2. Start the stack and wait until the services are healthy (`docker compose ... ps`). Baseline
   traffic starts about 10 s after `checkout-api` is up; give Prometheus a few minutes of it.

   ```powershell
   docker compose -f deploy/docker-compose.yml up -d --build
   docker compose -f deploy/docker-compose.yml ps
   ```

3. Deploy `checkout-api v2.14.3` (a copy-only change by `dev-alice`, PR #1423). The script adds the
   deployment to the Fake GitHub and recreates `mock-checkout-api` with
   `CHECKOUT_API_VERSION=v2.14.3`, so its `service.version` resource attribute changes.

   ```powershell
   .\mock-services\deploy.ps1
   ```
   ```bash
   ./mock-services/deploy.sh
   ```

   Options: `-Service`/`--service`, `-Version`, `-Author`, `-PrTitle`, `-PrNumber`,
   `-ChangedFiles`/`--file` (repeatable), `-NoRestart`/`--no-restart` (record only and print the
   `docker compose` command for the version label). The script refuses changed files that look like
   payment-client, timeout or retry code.

4. Wait a few minutes so the deploy and the fault are separated in time (the scenario has 12
   minutes), then make `payment-provider` slow. It is published on host port 8083:

   ```powershell
   Invoke-RestMethod -Method Post "http://localhost:8083/inject/slow?enabled=true&ms=5000"
   ```
   ```bash
   curl -X POST "http://localhost:8083/inject/slow?enabled=true&ms=5000"
   ```

   The gateway gives up after 2 s and returns 504, so checkout returns 5xx and logs
   `PaymentGatewayTimeoutException`.

5. Wait for the alert. The `checkout-api` 5xx ratio rises in Prometheus within a minute; the
   `HighErrorRate` rule then fires and Alertmanager posts it to the investigator (the rule and the
   webhook route are added in plan 2.3). Watch:

   - Prometheus alerts: <http://localhost:9090/alerts>
   - Alertmanager: <http://localhost:9093>
   - Investigator: `docker compose -f deploy/docker-compose.yml logs -f investigator`

6. Turn the fault off and stop the stack:

   ```powershell
   Invoke-RestMethod -Method Post "http://localhost:8083/inject/slow?enabled=false"
   docker compose -f deploy/docker-compose.yml down
   ```

A later plain `docker compose up` recreates `mock-checkout-api` with the default version
(`v1.0.0`) unless `CHECKOUT_API_VERSION` is set in the shell. Restarting `fake-github` drops the
recorded deployment; run the deploy script again.

## Fake GitHub API subset

WireMock (`fake-github`, `http://fake-github:8080` in the stack, `http://127.0.0.1:8089` on the
host) serves the GitHub REST endpoints below for the repository `moriarty-demo/checkout-platform`
(the compose default for `GITHUB_REPOSITORY`). Query parameters such as `environment` or `per_page`
are accepted and ignored. Baseline stubs are in `deploy/fake-github/mappings` and `__files`; the
deploy script adds runtime stubs with priority 1, which take precedence over the baseline
(priority 5). Other `/repos/...` paths return a GitHub-style 404.

| Endpoint | Returns |
|----------|---------|
| `GET /repos/{owner}/{repo}/deployments` | Deployments, newest first: `id`, `sha`, `ref` (`checkout-api-v2.14.3`), `task`, `payload.service`, `payload.version`, `environment` (`production`), `description`, `creator.login`, `created_at`, `statuses_url` |
| `GET /repos/{owner}/{repo}/deployments/{id}/statuses` | One status with `state: "success"` |
| `GET /repos/{owner}/{repo}/commits/{sha}` | `commit.message`, `commit.author`, `author.login`, `html_url`, `files[].filename` |
| `GET /repos/{owner}/{repo}/commits/{sha}/pulls` | The merged PR: `number`, `title`, `html_url`, `user.login`, `merged_at` |

Responses carry `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset` headers. The
baseline is `checkout-api v2.14.2` by `dev-bob` 26 hours before the request time (WireMock response
templating), outside the 24-hour change lookback. The service a deployment belongs to is
`payload.service`; one repository holds all three services.

Inspect the current stubs with `Invoke-RestMethod http://127.0.0.1:8089/__admin/mappings`.
