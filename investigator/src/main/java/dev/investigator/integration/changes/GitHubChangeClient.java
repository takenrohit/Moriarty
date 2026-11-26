package dev.investigator.integration.changes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.ConditionalOnIntegrationEndpoint;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * {@link ChangeClient} backed by the GitHub REST Deployments API (Req 6.1 to 6.5), and by the Fake GitHub of
 * the Compose stack, which serves the same endpoints (Req 6.6, CP-46, CP-60).
 *
 * <p>Created only with the {@code real} profile and a non-blank {@code investigator.integrations.changes.base-url}
 * ({@code GITHUB_API_URL}); otherwise the mock fallback in {@code IntegrationConfig} is used (Req 7.1, 7.2). The
 * repository is {@code investigator.integrations.changes.repository} ({@code GITHUB_REPOSITORY}, {@code owner/repo}).
 * The token ({@code GITHUB_TOKEN}) is sent as {@code Authorization: Bearer ...} by {@link RestClients} and is never
 * logged or put in a message (Req 6.3).
 *
 * <p>Per call: {@code GET /repos/{o}/{r}/deployments?per_page=100} (newest first), keep the deployments whose
 * {@code payload.service} is the service and whose {@code created_at} is in {@code [since, until]}, then for each
 * kept deployment's {@code sha} read {@code GET .../commits/{sha}} (changed files, commit message) and
 * {@code GET .../commits/{sha}/pulls} (merged PR title and link). A 404 or 422 on those two lookups (an unknown
 * commit) leaves the files and PR empty; any other HTTP error, a transport error or a malformed body throws
 * {@link IntegrationCallException} (Req 6.4). An exhausted rate limit (HTTP 403 or 429 with
 * {@code X-RateLimit-Remaining: 0}, or any 429) throws with {@link #RATE_LIMIT_HINT} (Req 6.5).
 */
@Component
@Profile("real")
@ConditionalOnIntegrationEndpoint("changes")
public class GitHubChangeClient implements ChangeClient {

    static final String INTEGRATION = "changes";
    static final String REPOSITORY_PROPERTY = "investigator.integrations.changes.repository";
    static final String DEPLOYMENTS_PATH = "/repos/{owner}/{repo}/deployments";
    static final String COMMIT_PATH = "/repos/{owner}/{repo}/commits/{sha}";
    static final String PULLS_PATH = "/repos/{owner}/{repo}/commits/{sha}/pulls";
    static final String ACCEPT = "application/vnd.github+json";
    static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    static final String API_VERSION = "2022-11-28";
    static final int PER_PAGE = 100;

    /** Hint returned to the model when the GitHub rate limit is exhausted (constant text). */
    public static final String RATE_LIMIT_HINT = "the GitHub API rate limit is exhausted; do not call"
            + " getRecentChanges again in this investigation and continue with metrics, logs and traces";

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern SHA = Pattern.compile("[0-9A-Fa-f]{7,64}");
    private static final int MAX_ERROR_DETAIL = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;
    private final String owner;
    private final String repo;
    private final int maxChangedFiles;

    public GitHubChangeClient(RestClients clients, InvestigatorProperties properties,
            @Value("${" + REPOSITORY_PROPERTY + ":}") String repository) {
        if (repository == null || !REPOSITORY.matcher(repository.strip()).matches()) {
            throw new IllegalArgumentException(REPOSITORY_PROPERTY + " must be set to owner/repo (GITHUB_REPOSITORY)");
        }
        String[] parts = repository.strip().split("/");
        this.rest = clients.changes();
        this.owner = parts[0];
        this.repo = parts[1];
        this.maxChangedFiles = properties.tools().maxChangedFiles();
    }

    @Override
    public List<ChangeRecord> recentChanges(String service, Instant since, Instant until) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(until, "until");
        if (since.isAfter(until)) {
            throw new IllegalArgumentException("since must not be after until");
        }
        JsonNode deployments = get("deployments", false,
                b -> b.path(DEPLOYMENTS_PATH).queryParam("per_page", PER_PAGE).build(owner, repo));
        return parse(deployments, service, since, until, maxChangedFiles,
                sha -> get("commit", true, b -> b.path(COMMIT_PATH).build(owner, repo, sha)),
                sha -> get("commit pulls", true, b -> b.path(PULLS_PATH).build(owner, repo, sha)));
    }

    /** One GET with the GitHub headers; {@code null} for a 404/422 when {@code missingIsEmpty}. */
    private JsonNode get(String what, boolean missingIsEmpty, Function<UriBuilder, URI> uri) {
        try {
            return rest.get()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, ACCEPT)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .exchange((request, response) -> readBody(what, response.getStatusCode(), response.getHeaders(),
                            response.getBody().readAllBytes(), missingIsEmpty));
        } catch (RestClientException e) {
            throw new IntegrationCallException(INTEGRATION, what + " request failed", e);
        }
    }

    /** Checks rate limit and HTTP status, then parses the body as JSON. */
    static JsonNode readBody(String what, HttpStatusCode status, HttpHeaders headers, byte[] bytes,
            boolean missingIsEmpty) throws IOException {
        int code = status.value();
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        if (code == 429 || (code == 403 && remaining != null && "0".equals(remaining.strip()))) {
            throw IntegrationCallException.withHint(INTEGRATION,
                    "GitHub API rate limit exhausted (HTTP " + code + resetDetail(headers) + ")", RATE_LIMIT_HINT);
        }
        if (missingIsEmpty && (code == 404 || code == 422)) {
            return null;
        }
        if (status.isError()) {
            throw new IntegrationCallException(INTEGRATION, what + " returned HTTP " + code + errorDetail(bytes));
        }
        try {
            JsonNode json = JSON.readTree(bytes);
            if (json != null && (json.isObject() || json.isArray())) {
                return json;
            }
        } catch (JsonProcessingException e) {
            // reported below
        }
        throw new IntegrationCallException(INTEGRATION, what + " returned a body that is not JSON");
    }

    /**
     * Maps a deployments body and its commit lookups to {@link ChangeRecord}s, newest first (Req 6.1, 6.2, 6.8).
     *
     * <p>A deployment is kept when {@code payload.service} equals {@code service} and {@code created_at} is in
     * {@code [since, until]}. Its version is {@code payload.version}, else {@code ref}; its time
     * {@code created_at}; its author {@code creator.login}. For its {@code sha}, {@code commitBySha} gives the
     * commit ({@code files[].filename}, at most {@code maxFiles}, and {@code commit.message}) and
     * {@code pullsBySha} the associated pull requests; either may return {@code null} (unknown commit). The PR title
     * and link come from the first merged pull request (else the first one), else the commit message's first line
     * and the commit {@code html_url}. Each sha is looked up at most once.
     *
     * @throws IntegrationCallException when the body is not a deployments array or a kept deployment is malformed
     */
    public static List<ChangeRecord> parse(JsonNode deployments, String service, Instant since, Instant until,
            int maxFiles, Function<String, JsonNode> commitBySha, Function<String, JsonNode> pullsBySha) {
        Objects.requireNonNull(deployments, "deployments");
        if (!deployments.isArray()) {
            throw new IntegrationCallException(INTEGRATION, "deployments is not an array");
        }
        record Kept(JsonNode deployment, Instant at) {
        }
        List<Kept> kept = new ArrayList<>();
        for (JsonNode deployment : deployments) {
            if (!service.equals(deployment.path("payload").path("service").asText(null))) {
                continue;
            }
            Instant at = time(deployment.path("created_at"));
            if (!at.isBefore(since) && !at.isAfter(until)) {
                kept.add(new Kept(deployment, at));
            }
        }
        kept.sort(Comparator.comparing(Kept::at).reversed());

        Map<String, JsonNode> commits = new HashMap<>();
        Map<String, JsonNode> pulls = new HashMap<>();
        List<ChangeRecord> records = new ArrayList<>(kept.size());
        for (Kept k : kept) {
            JsonNode d = k.deployment();
            String version = text(d.path("payload").path("version"));
            if (version == null) {
                version = text(d.path("ref"));
            }
            String sha = text(d.path("sha"));
            JsonNode commit = null;
            JsonNode prs = null;
            if (sha != null && SHA.matcher(sha).matches()) {
                commit = commits.computeIfAbsent(sha, s -> orMissing(commitBySha.apply(s)));
                prs = pulls.computeIfAbsent(sha, s -> orMissing(pullsBySha.apply(s)));
            }
            JsonNode pr = pullRequest(prs);
            String title = pr == null ? firstLine(text(commit == null ? null : commit.path("commit").path("message")))
                    : text(pr.path("title"));
            String link = pr == null ? null : text(pr.path("html_url"));
            if (link == null && commit != null) {
                link = text(commit.path("html_url"));
            }
            records.add(new ChangeRecord(service, version, k.at(), text(d.path("creator").path("login")), title,
                    link, files(commit, maxFiles)));
        }
        return records;
    }

    private static JsonNode orMissing(JsonNode node) {
        return node == null ? JSON.missingNode() : node;
    }

    private static JsonNode pullRequest(JsonNode prs) {
        if (prs == null || !prs.isArray() || prs.isEmpty()) {
            return null;
        }
        for (JsonNode pr : prs) {
            if (text(pr.path("merged_at")) != null) {
                return pr;
            }
        }
        return prs.get(0);
    }

    private static List<String> files(JsonNode commit, int maxFiles) {
        List<String> files = new ArrayList<>();
        if (commit == null) {
            return files;
        }
        for (JsonNode file : commit.path("files")) {
            if (files.size() >= maxFiles) {
                break;
            }
            String name = text(file.path("filename"));
            if (name != null) {
                files.add(name);
            }
        }
        return files;
    }

    private static Instant time(JsonNode node) {
        try {
            return Instant.parse(node.asText(""));
        } catch (DateTimeParseException e) {
            throw new IntegrationCallException(INTEGRATION, "deployment created_at is not an ISO-8601 time");
        }
    }

    /** Non-blank text of {@code node}, else {@code null}. */
    private static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        String line = text.lines().findFirst().orElse("").strip();
        return line.isEmpty() ? null : line;
    }

    private static String resetDetail(HttpHeaders headers) {
        String reset = headers.getFirst("X-RateLimit-Reset");
        try {
            return reset == null ? "" : ", resets at " + Instant.ofEpochSecond(Long.parseLong(reset.strip()));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** {@code ": <first 200 characters of the body>"}, or empty for an empty body. */
    private static String errorDetail(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return "";
        }
        return ": " + (text.length() > MAX_ERROR_DETAIL ? text.substring(0, MAX_ERROR_DETAIL) + "..." : text);
    }
}
