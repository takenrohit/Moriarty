// Feature: real-integrations, Property 5: GitHub deployment parsing preserves deployments
package dev.investigator.integration.changes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.testsupport.DomainGenerators;
import dev.investigator.testsupport.PropertySeeds;
import dev.investigator.testsupport.ResponseGenerators;
import dev.investigator.testsupport.ResponseGenerators.GitHubChange;
import dev.investigator.testsupport.ResponseGenerators.GitHubDeployments;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 5 over {@link GitHubChangeClient#parse}: every deployment of the service in the window becomes one
 * {@link ChangeRecord}, newest first, with its version and timestamp, and at most {@code maxFiles} changed files
 * (the commit's first ones); other services, deployments without a payload and times outside the window are dropped.
 */
class GitHubParsingPropertyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVICE = "checkout-api";

    /** **Validates: Requirements 6.2, 6.8** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void parsingPreservesDeploymentCountVersionsTimesAndCapsFiles(long seed) {
        PropertySeeds.check(seed, r -> {
            Instant until = DomainGenerators.ANCHOR;
            Instant since = until.minus(Duration.ofHours(r.nextInt(1, 25)));
            int maxFiles = r.nextInt(4) == 0 ? 0 : r.nextInt(1, 25);
            GitHubDeployments generated = ResponseGenerators.githubDeployments(r, SERVICE, since, until, maxFiles);

            List<ChangeRecord> records = GitHubChangeClient.parse(JSON.readTree(generated.body()), SERVICE, since,
                    until, maxFiles, generated.commits()::get, generated.pulls()::get);

            List<GitHubChange> expected = generated.expected();
            assertThat(records).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                ChangeRecord actual = records.get(i);
                assertThat(actual.service()).isEqualTo(SERVICE);
                assertThat(actual.version()).isEqualTo(expected.get(i).version());
                assertThat(actual.deployedAt()).isEqualTo(expected.get(i).deployedAt());
                assertThat(actual.changedFiles()).hasSizeLessThanOrEqualTo(maxFiles)
                        .isEqualTo(expected.get(i).files());
            }
        });
    }
}
