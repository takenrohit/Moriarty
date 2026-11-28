package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static guard over the console's script and templates (Req 1.2, 9.2): the script never writes markup
 * strings or runs strings as code, templates never emit unescaped text, no page loads anything from an
 * external host, and no secret-like value is shipped in a static file. Reads the source files from the
 * module directory, where Maven runs the tests.
 */
class ConsoleJsGuardTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path CONSOLE_JS = RESOURCES.resolve(Path.of("static", "js", "console.js"));

    /** An href or src attribute (including th:href / th:src, or a JS property assignment) whose value holds an absolute URL. */
    private static final Pattern EXTERNAL_URL =
            Pattern.compile("(?i)\\b(?:th:)?(?:href|src)\\s*=\\s*[\"'][^\"']*https?://");

    private static final List<String> SECRET_MARKERS = List.of("Bearer ", "sk-", "jdbc:", "password=");

    @Test
    void consoleScriptNeverWritesMarkupOrEvaluatesStrings() throws IOException {
        String js = read(CONSOLE_JS);

        assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "eval");
        assertThat(js).contains("textContent");
    }

    @Test
    void consoleScriptPollsAtTheSpecifiedIntervalsAndFetchesTheFragment() throws IOException {
        String js = read(CONSOLE_JS);

        assertThat(js).contains("3000", "10000", "/incidents/fragment");
    }

    @Test
    void templatesNeverUseUnescapedText() throws IOException {
        for (Path file : templates()) {
            assertThat(read(file)).as(file.toString()).doesNotContain("th:utext");
        }
    }

    @Test
    void templatesAndStaticFilesLoadNothingFromExternalHosts() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : scannedFiles()) {
            Matcher m = EXTERNAL_URL.matcher(read(file));
            while (m.find()) {
                findings.add(file + ": " + m.group());
            }
        }
        assertThat(findings).isEmpty();
    }

    @Test
    void templatesAndStaticFilesContainNoSecretLikeStrings() throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String content = read(file);
            for (String marker : SECRET_MARKERS) {
                if (content.contains(marker)) {
                    findings.add(file + ": " + marker);
                }
            }
        }
        assertThat(findings).isEmpty();
    }

    private static List<Path> templates() throws IOException {
        List<Path> files = filesUnder(RESOURCES.resolve("templates"));
        files.removeIf(p -> !p.getFileName().toString().endsWith(".html"));
        assertThat(files).as("templates found").isNotEmpty();
        return files;
    }

    private static List<Path> scannedFiles() throws IOException {
        List<Path> files = new ArrayList<>(templates());
        List<Path> statics = filesUnder(RESOURCES.resolve("static"));
        assertThat(statics).as("static files found").contains(CONSOLE_JS);
        files.addAll(statics);
        return files;
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return new ArrayList<>(walk.filter(Files::isRegularFile).sorted().toList());
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
