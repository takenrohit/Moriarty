package dev.investigator.runbook;

import dev.investigator.evidence.Headlines;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Runbook_Service (Req 12.1, 12.3, 12.6, 12.7): loads the Markdown runbooks at startup and selects the
 * best one for a service and symptom.
 *
 * <p>Scoring: 10 points when the runbook's service equals the requested service ignoring case, plus
 * 1 point per keyword of the symptom that equals a token of the runbook's symptoms, tags or title.
 * Keywords are the distinct lowercase tokens of the symptom split on {@code [^a-z0-9]+}, keeping tokens
 * of 3 or more characters. The highest score wins; ties go to the smaller file name. A best score of
 * 0 means no runbook matches.
 */
@Component
public class RunbookService implements RunbookCatalog {

    /** Where the runbooks are loaded from. */
    public static final String LOCATION_PATTERN = "classpath:runbooks/*.md";

    static final int SERVICE_POINTS = 10;
    static final int MIN_KEYWORD_LENGTH = 3;
    static final int MAX_STEPS = 10;
    static final int MAX_STEP_LENGTH = 200;
    static final int MAX_BODY_EXCERPT = 1000;

    private static final Logger log = LoggerFactory.getLogger(RunbookService.class);
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*]|\\d+\\.)\\s+(.*)$");

    private final List<LoadedRunbook> runbooks;

    /** Loads every runbook matching {@link #LOCATION_PATTERN}. */
    @Autowired
    public RunbookService(ResourcePatternResolver resolver) {
        this(load(resolver, LOCATION_PATTERN));
        log.info("loaded {} runbooks from {}", runbooks.size(), LOCATION_PATTERN);
    }

    /** Uses the given runbooks, ordered by file name. */
    public RunbookService(List<LoadedRunbook> runbooks) {
        this.runbooks = runbooks.stream().sorted(Comparator.comparing(LoadedRunbook::fileName)).toList();
    }

    /**
     * Parses every resource matching {@code locationPattern}. A file that cannot be read or parsed is
     * skipped with a warning naming the file (Req 12.3).
     */
    public static List<LoadedRunbook> load(ResourcePatternResolver resolver, String locationPattern) {
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            log.warn("cannot list runbooks at {}: {}", locationPattern, e.getMessage());
            return List.of();
        }
        RunbookParser parser = new RunbookParser();
        List<LoadedRunbook> loaded = new ArrayList<>();
        Arrays.stream(resources)
                .sorted(Comparator.comparing(r -> String.valueOf(r.getFilename())))
                .forEach(resource -> {
                    String fileName = resource.getFilename();
                    if (fileName == null) {
                        return;
                    }
                    try {
                        String text = resource.getContentAsString(StandardCharsets.UTF_8);
                        loaded.add(new LoadedRunbook(fileName, parser.parse(text)));
                    } catch (RunbookFormatException e) {
                        log.warn("skipping runbook file {}: {}", fileName, e.getMessage());
                    } catch (IOException | RuntimeException e) {
                        log.warn("skipping runbook file {}: cannot read file ({})", fileName,
                                e.getClass().getSimpleName());
                    }
                });
        return loaded;
    }

    /** The loaded runbooks ordered by file name. */
    public List<LoadedRunbook> all() {
        return runbooks;
    }

    /** The highest-scoring runbook, ties to the smaller file name; empty when every score is 0. */
    public Optional<ScoredRunbook> find(String service, String symptom) {
        ScoredRunbook best = null;
        for (LoadedRunbook loaded : runbooks) {
            int score = score(loaded.runbook(), service, symptom);
            if (score > 0 && (best == null || score > best.score())) {
                best = new ScoredRunbook(loaded, score);
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public Optional<RunbookMatch> bestMatch(String service, String symptom) {
        return find(service, symptom).map(found -> new RunbookMatch(found.fileName(), found.runbook().title(),
                found.runbook().service(), steps(found.runbook().body())));
    }

    /** The score of {@code runbook} for {@code service} and {@code symptom} (Req 12.6). */
    public static int score(Runbook runbook, String service, String symptom) {
        int score = service != null && runbook.service().equalsIgnoreCase(service) ? SERVICE_POINTS : 0;
        Set<String> keywords = keywords(symptom);
        if (keywords.isEmpty()) {
            return score;
        }
        Set<String> tokens = new HashSet<>(tokens(runbook.title()));
        runbook.symptoms().forEach(s -> tokens.addAll(tokens(s)));
        runbook.tags().forEach(t -> tokens.addAll(tokens(t)));
        for (String keyword : keywords) {
            if (tokens.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    /** The distinct lowercase tokens of {@code symptom} with at least 3 characters, in order. */
    static Set<String> keywords(String symptom) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : tokens(symptom)) {
            if (token.length() >= MIN_KEYWORD_LENGTH) {
                keywords.add(token);
            }
        }
        return keywords;
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(TOKEN_SEPARATOR.split(text.toLowerCase(Locale.ROOT)))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /**
     * The steps of a runbook body: the text of up to {@value #MAX_STEPS} list items ({@code - },
     * {@code * } or {@code N. }), each truncated to {@value #MAX_STEP_LENGTH} code points; if the body
     * has no list items, its first {@value #MAX_BODY_EXCERPT} code points as one step.
     */
    public static List<String> steps(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        for (String line : body.split("\r?\n", -1)) {
            var matcher = LIST_ITEM.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String text = matcher.group(1).strip();
            if (text.isEmpty()) {
                continue;
            }
            steps.add(Headlines.truncate(text, MAX_STEP_LENGTH));
            if (steps.size() == MAX_STEPS) {
                break;
            }
        }
        if (steps.isEmpty()) {
            steps.add(Headlines.truncate(body.strip(), MAX_BODY_EXCERPT));
        }
        return List.copyOf(steps);
    }
}
