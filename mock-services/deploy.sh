#!/usr/bin/env bash
# Deploy_Script: record a new deployment of a mock service in the Fake GitHub and bump the
# service's version label (Req 2.8). Same behaviour as deploy.ps1; needs bash, curl and docker.
# See mock-services/README.md for the demo sequence.
#
# 1. Reads the current deployment list from the Fake GitHub (WireMock, host 127.0.0.1:8089).
# 2. Adds runtime stubs through WireMock's admin API (POST /__admin/mappings). They are held in
#    memory and disappear when the fake-github container restarts:
#      GET /repos/{repo}/deployments                -> the new deployment first, then the earlier ones
#      GET /repos/{repo}/deployments/{id}/statuses  -> one "success" status
#      GET /repos/{repo}/commits/{sha}              -> the commit with the changed files
#      GET /repos/{repo}/commits/{sha}/pulls        -> the merged pull request with its title
# 3. Recreates the service's container with <SERVICE>_VERSION=<version> so its OpenTelemetry
#    resource attribute service.version changes. --no-restart only prints the command.
#
# Changed files must not touch payment-client, timeout or retry code; the script refuses them.
#
# Usage: mock-services/deploy.sh [--service checkout-api|payment-gateway|payment-provider]
#          [--version v2.14.3] [--author dev-alice] [--pr-title "..."] [--pr-number 1423]
#          [--file path]... [--repository owner/repo] [--environment production]
#          [--github-url http://127.0.0.1:8089] [--no-restart]
set -euo pipefail

service="checkout-api"
version="v2.14.3"
author="dev-alice"
pr_title="Update order confirmation copy"
pr_number="1423"
repository="moriarty-demo/checkout-platform"
environment="production"
github_url="http://127.0.0.1:8089"
no_restart="false"
files=()

die() { printf 'deploy.sh: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --service) service="${2:?--service needs a value}"; shift 2 ;;
    --version) version="${2:?--version needs a value}"; shift 2 ;;
    --author) author="${2:?--author needs a value}"; shift 2 ;;
    --pr-title) pr_title="${2:?--pr-title needs a value}"; shift 2 ;;
    --pr-number) pr_number="${2:?--pr-number needs a value}"; shift 2 ;;
    --file) files+=("${2:?--file needs a value}"); shift 2 ;;
    --repository) repository="${2:?--repository needs a value}"; shift 2 ;;
    --environment) environment="${2:?--environment needs a value}"; shift 2 ;;
    --github-url) github_url="${2:?--github-url needs a value}"; shift 2 ;;
    --no-restart) no_restart="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

# --- Validate inputs ---------------------------------------------------------------------------

case "$service" in
  checkout-api|payment-gateway|payment-provider) ;;
  *) die "--service must be checkout-api, payment-gateway or payment-provider" ;;
esac
[[ "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$ ]] || die "invalid --version: $version"
[[ "$author" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,38}$ ]] || die "invalid --author: $author"
[[ "$pr_number" =~ ^[1-9][0-9]{0,5}$ ]] || die "invalid --pr-number: $pr_number"
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "invalid --repository: $repository"
[[ "$environment" =~ ^[A-Za-z0-9_.-]+$ ]] || die "invalid --environment: $environment"
[[ "$github_url" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]] || die "invalid --github-url: $github_url"
github_url="${github_url%/}"
if [ -z "$pr_title" ] || [ "${#pr_title}" -gt 200 ] || [[ "$pr_title" =~ [[:cntrl:]] ]]; then
  die "--pr-title must be 1 to 200 characters with no control characters"
fi

if [ "${#files[@]}" -eq 0 ]; then
  files=("$service/src/main/resources/messages.properties"
         "$service/src/main/resources/templates/order-confirmation.html")
fi
# Req 2.8: the demo deploy is harmless, so it must not touch payment-client, timeout or retry code.
forbidden='payment[-_]?(gateway[-_]?)?client|timeout|retr(y|ies)'
for f in "${files[@]}"; do
  if ! [[ "$f" =~ ^[A-Za-z0-9_][A-Za-z0-9_./-]{0,255}$ ]] || [[ "$f" == *..* ]]; then
    die "invalid changed-file path: $f"
  fi
  lower="$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')"
  if [[ "$lower" =~ $forbidden ]]; then
    die "changed file '$f' looks like payment-client, timeout or retry code; the demo deploy must not touch it (Req 2.8)"
  fi
done

command -v curl >/dev/null 2>&1 || die "curl is required"

# --- Build the deployment record ---------------------------------------------------------------

# JSON string escaping for values that passed validation (no control characters).
json_str() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '"%s"' "$s"
}

iso_at() {  # $1 = epoch seconds
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}

epoch="$(date -u +%s)"
created_at="$(iso_at "$epoch")"
merged_at="$(iso_at $((epoch - 300)))"
pr_opened_at="$(iso_at $((epoch - 10800)))"
sha="$(od -An -N20 -tx1 /dev/urandom | tr -d ' \n')"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "could not generate a commit sha"
deployment_id="$epoch"
status_id=$((epoch + 1))
production="false"; [ "$environment" = "production" ] && production="true"
# Links inside the bodies use the in-network URL the investigator reads (http://fake-github:8080).
api_repo="http://fake-github:8080/repos/$repository"
web_repo="https://github.com/$repository"
user_json="{\"login\":$(json_str "$author"),\"type\":\"User\"}"

deployment_json="{\"url\":\"$api_repo/deployments/$deployment_id\",\"id\":$deployment_id,\"sha\":\"$sha\",\
\"ref\":$(json_str "$service-$version"),\"task\":\"deploy\",\
\"payload\":{\"service\":$(json_str "$service"),\"version\":$(json_str "$version")},\
\"original_environment\":$(json_str "$environment"),\"environment\":$(json_str "$environment"),\
\"description\":$(json_str "Deploy $service $version"),\"creator\":$user_json,\
\"created_at\":\"$created_at\",\"updated_at\":\"$created_at\",\
\"statuses_url\":\"$api_repo/deployments/$deployment_id/statuses\",\"repository_url\":\"$api_repo\",\
\"transient_environment\":false,\"production_environment\":$production}"

statuses_json="[{\"url\":\"$api_repo/deployments/$deployment_id/statuses/$status_id\",\"id\":$status_id,\
\"state\":\"success\",\"description\":$(json_str "Deployed $service $version"),\
\"environment\":$(json_str "$environment"),\"creator\":$user_json,\
\"created_at\":\"$created_at\",\"updated_at\":\"$created_at\",\
\"deployment_url\":\"$api_repo/deployments/$deployment_id\",\"repository_url\":\"$api_repo\"}]"

files_json=""
for f in "${files[@]}"; do
  [ -n "$files_json" ] && files_json="$files_json,"
  files_json="$files_json{\"filename\":$(json_str "$f"),\"status\":\"modified\",\"additions\":3,\"deletions\":1,\"changes\":4}"
done
n="${#files[@]}"
commit_json="{\"url\":\"$api_repo/commits/$sha\",\"sha\":\"$sha\",\"html_url\":\"$web_repo/commit/$sha\",\
\"commit\":{\"message\":$(json_str "$pr_title (#$pr_number)"),\
\"author\":{\"name\":$(json_str "$author"),\"email\":$(json_str "$author@users.noreply.github.com"),\"date\":\"$merged_at\"},\
\"committer\":{\"name\":\"GitHub\",\"email\":\"noreply@github.com\",\"date\":\"$merged_at\"}},\
\"author\":$user_json,\"committer\":{\"login\":\"web-flow\",\"type\":\"User\"},\"parents\":[],\
\"stats\":{\"total\":$((4 * n)),\"additions\":$((3 * n)),\"deletions\":$n},\"files\":[$files_json]}"

pulls_json="[{\"url\":\"$api_repo/pulls/$pr_number\",\"html_url\":\"$web_repo/pull/$pr_number\",\
\"number\":$pr_number,\"state\":\"closed\",\"title\":$(json_str "$pr_title"),\"user\":$user_json,\
\"created_at\":\"$pr_opened_at\",\"merged_at\":\"$merged_at\",\"merge_commit_sha\":\"$sha\",\
\"head\":{\"ref\":$(json_str "$author/$service-$version")},\"base\":{\"ref\":\"main\"}}]"

# --- Talk to the Fake GitHub -------------------------------------------------------------------

headers_json="{\"Content-Type\":\"application/json; charset=utf-8\",\"X-GitHub-Api-Version\":\"2022-11-28\",\
\"X-RateLimit-Limit\":\"5000\",\"X-RateLimit-Remaining\":\"4999\",\"X-RateLimit-Reset\":\"$((epoch + 3600))\"}"

# Priority 1 beats the file-based baseline (priority 5); among equal priorities WireMock serves
# the most recently added stub, so the latest deploy wins.
add_stub() {  # $1 = name, $2 = url path, $3 = JSON body
  local mapping
  mapping="{\"name\":$(json_str "$1"),\"priority\":1,\
\"request\":{\"method\":\"GET\",\"urlPath\":$(json_str "$2")},\
\"response\":{\"status\":200,\"headers\":$headers_json,\"jsonBody\":$3},\
\"metadata\":{\"source\":\"deploy-script\",\"service\":$(json_str "$service"),\"version\":$(json_str "$version")}}"
  printf '%s' "$mapping" | curl -sS --fail -o /dev/null -X POST \
    -H 'Content-Type: application/json; charset=utf-8' --data-binary @- \
    "$github_url/__admin/mappings" || die "could not add stub '$1' via $github_url/__admin/mappings"
}

deployments_path="/repos/$repository/deployments"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
code="$(curl -sS -o "$tmp" -w '%{http_code}' -H 'Accept: application/vnd.github+json' \
  "$github_url$deployments_path")" \
  || die "cannot reach $github_url$deployments_path. Is the stack up? docker compose -f deploy/docker-compose.yml up -d --build"
case "$code" in
  200) existing="$(tr -d '\r\n' < "$tmp")" ;;
  404) existing="[]" ;;
  *) die "unexpected HTTP $code from $github_url$deployments_path" ;;
esac

# Trim surrounding whitespace and splice the new deployment in front of the earlier ones.
existing="${existing#"${existing%%[![:space:]]*}"}"
existing="${existing%"${existing##*[![:space:]]}"}"
[[ "$existing" == \[*\] ]] || die "unexpected deployments response from the Fake GitHub (expected a JSON array)"
earlier="${existing:1:${#existing}-2}"
earlier="${earlier#"${earlier%%[![:space:]]*}"}"
if [ -n "$earlier" ]; then
  list_json="[$deployment_json,$earlier]"
else
  list_json="[$deployment_json]"
fi

# Commit, PR and status stubs first, so the list never names a sha the fake cannot serve.
add_stub "GET commit $sha ($service $version)" "/repos/$repository/commits/$sha" "$commit_json"
add_stub "GET commit pulls $sha ($service $version)" "/repos/$repository/commits/$sha/pulls" "$pulls_json"
add_stub "GET deployment statuses $deployment_id ($service $version)" "$deployments_path/$deployment_id/statuses" "$statuses_json"
add_stub "GET deployments: $service $version" "$deployments_path" "$list_json"

echo "Recorded deployment $deployment_id of $service $version at $created_at (sha $sha, PR #$pr_number by $author)."
echo "Check: curl -s $github_url$deployments_path"

# --- Bump the version label --------------------------------------------------------------------

version_var="$(printf '%s' "$service" | tr 'a-z-' 'A-Z_')_VERSION"
compose_service="mock-$service"
script_dir="$(cd "$(dirname "$0")" && pwd)"
compose_file="$script_dir/../deploy/docker-compose.yml"
manual="$version_var=$version docker compose -f deploy/docker-compose.yml up -d --no-deps --no-build $compose_service"

if [ "$no_restart" = "true" ]; then
  echo "Version label not changed (--no-restart). To apply it, run from the repository root:"
  echo "  $manual"
  exit 0
fi

echo "Recreating $compose_service with $version_var=$version ..."
if ! env "$version_var=$version" docker compose -f "$compose_file" up -d --no-deps --no-build "$compose_service"; then
  die "docker compose failed. The deployment is recorded; apply the label manually: $manual"
fi
echo "$compose_service now reports service.version=$version. A later plain 'docker compose up' resets it unless $version_var is set."
