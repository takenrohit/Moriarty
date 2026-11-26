// Feature: reliability, Property 3: Redaction removes secrets, reports categories, and is idempotent
package dev.investigator.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.testsupport.PropertySeeds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 3 over {@link RegexRedactor#redact}: nested payloads whose string values embed a corpus of fake
 * secrets (JWTs, bearer tokens, AWS keys, credential pairs, emails, Luhn-valid card numbers) in generated
 * surrounding text lose every raw secret, report exactly the embedded categories with status
 * {@code applied}, and are unchanged by a second redaction; clean payloads (fixed words and non-Luhn digit
 * runs) come back equal with status {@code not_applied}.
 */
class RegexRedactorPropertyTest {

    private static final String[] WORDS = {"checkout", "latency", "rose", "after", "deploy", "pool", "the",
        "service", "gateway", "errors", "upstream", "timeout", "retry", "p99", "status", "503", "42"};
    private static final String[] CATEGORIES = {"jwt", "bearer_token", "aws_key", "credential", "email", "card"};
    private static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~+/";
    private static final String UPPER_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String VALUE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%*";

    private final RegexRedactor redactor = new RegexRedactor();

    /** **Validates: Requirements 5.1, 5.2, 5.3** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void redactionRemovesSecretsReportsCategoriesAndIsIdempotent(long seed) {
        PropertySeeds.check(seed, r -> {
            boolean withSecrets = r.nextInt(3) > 0;
            List<String> secrets = new ArrayList<>();
            Set<String> expected = new LinkedHashSet<>();
            Map<String, Object> payload = map(r, 0, withSecrets, secrets, expected);
            Map<String, Object> snapshot = deepCopy(payload);

            RedactionResult result = redactor.redact(payload);

            assertThat(payload).as("input not modified").isEqualTo(snapshot);
            if (expected.isEmpty()) {
                assertThat(result.status()).isEqualTo(RedactionResult.NOT_APPLIED);
                assertThat(result.categories()).isEmpty();
                assertThat(result.payload()).isEqualTo(payload);
            } else {
                assertThat(result.status()).isEqualTo(RedactionResult.APPLIED);
                assertThat(result.categories()).containsExactlyInAnyOrderElementsOf(expected);
                List<String> strings = strings(result.payload(), new ArrayList<>());
                for (String secret : secrets) {
                    assertThat(strings).as("raw secret %s", secret).noneMatch(s -> s.contains(secret));
                }
            }

            RedactionResult again = redactor.redact(result.payload());
            assertThat(again.payload()).as("idempotent").isEqualTo(result.payload());
            assertThat(again.status()).as("placeholders match no pattern").isEqualTo(RedactionResult.NOT_APPLIED);
        });
    }

    /** Phase 3 exit criterion: a log line with a fake card number and a bearer token gets both redacted. */
    @Test
    void logLineWithCardAndBearerTokenIsRedacted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("line", "POST /pay card=4111 1111 1111 1111 auth=\"Authorization: Bearer abc123DEF456ghi789\"");

        RedactionResult result = redactor.redact(payload);

        String line = (String) result.payload().get("line");
        assertThat(line).doesNotContain("4111 1111 1111 1111").doesNotContain("abc123DEF456ghi789")
                .contains("[REDACTED:card]").contains("Bearer [REDACTED:bearer_token]");
        assertThat(result.categories()).contains("card", "bearer_token");
        assertThat(result.status()).isEqualTo(RedactionResult.APPLIED);
    }

    @Test
    void nonLuhnDigitsEvidenceIdsAndNonStringsAreLeftAlone() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidenceId", "ev-inc-123456789012-1");
        payload.put("order", "order 4111111111111112 failed");
        payload.put("count", 4111111111111111L);
        payload.put("nested", List.of(Map.of("ok", true)));

        RedactionResult result = redactor.redact(payload);

        assertThat(result.status()).isEqualTo(RedactionResult.NOT_APPLIED);
        assertThat(result.payload()).isEqualTo(payload);
    }

    @Test
    void jsonStyleCredentialAndCardExpiryAreRedacted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", "{\"password\": \"hunter2!\", \"card\": \"4111-1111-1111-1111 12/27\"}");

        RedactionResult result = redactor.redact(payload);

        assertThat(result.payload().get("body"))
                .isEqualTo("{\"password\": \"[REDACTED:credential]\", \"card\": \"[REDACTED:card] 12/27\"}");
        assertThat(result.categories()).containsExactlyInAnyOrder("credential", "card");
    }

    // --- generators -------------------------------------------------------------------------------

    private static Map<String, Object> map(SplittableRandom r, int depth, boolean withSecrets, List<String> secrets,
            Set<String> expected) {
        Map<String, Object> map = new LinkedHashMap<>();
        int size = 1 + r.nextInt(4);
        for (int i = 0; i < size; i++) {
            map.put("k" + i, value(r, depth, withSecrets, secrets, expected));
        }
        return map;
    }

    private static Object value(SplittableRandom r, int depth, boolean withSecrets, List<String> secrets,
            Set<String> expected) {
        int kind = r.nextInt(depth >= 2 ? 5 : 7);
        return switch (kind) {
            case 0 -> r.nextLong();
            case 1 -> r.nextBoolean();
            case 2 -> null;
            case 3, 4 -> text(r, withSecrets && r.nextInt(2) == 0, secrets, expected);
            case 5 -> map(r, depth + 1, withSecrets, secrets, expected);
            default -> {
                List<Object> list = new ArrayList<>();
                int size = r.nextInt(4);
                for (int i = 0; i < size; i++) {
                    list.add(value(r, depth + 1, withSecrets, secrets, expected));
                }
                yield list;
            }
        };
    }

    /** Clean words around zero or one secret; segments always start and end with a word. */
    private static String text(SplittableRandom r, boolean secret, List<String> secrets, Set<String> expected) {
        String before = clean(r);
        if (!secret) {
            return before;
        }
        String category = CATEGORIES[r.nextInt(CATEGORIES.length)];
        String embedded = secret(r, category, secrets);
        expected.add(category);
        return before + " " + embedded + " " + clean(r);
    }

    /** Fixed words, with non-Luhn 13 to 19 digit runs that never sit next to another number. */
    private static String clean(SplittableRandom r) {
        StringBuilder out = new StringBuilder("upstream");
        int words = r.nextInt(6);
        for (int i = 0; i < words; i++) {
            if (r.nextInt(4) == 0) {
                out.append(' ').append(nonLuhnDigits(r)).append(" latency");
            } else {
                out.append(' ').append(WORDS[r.nextInt(WORDS.length - 2)]);
            }
        }
        return out.toString();
    }

    /** Returns the embedded form and records the raw secret values that must disappear. */
    private static String secret(SplittableRandom r, String category, List<String> secrets) {
        switch (category) {
            case "jwt" -> {
                String jwt = "eyJ" + chars(r, B64URL, 10, 30) + ".eyJ" + chars(r, B64URL, 10, 40) + "."
                        + chars(r, B64URL, 20, 43);
                secrets.add(jwt);
                return jwt;
            }
            case "bearer_token" -> {
                String token = chars(r, TOKEN, 20, 40);
                secrets.add(token);
                return (r.nextBoolean() ? "Authorization: Bearer " : "bearer ") + token;
            }
            case "aws_key" -> {
                String key = (r.nextBoolean() ? "AKIA" : "ASIA") + chars(r, UPPER_DIGITS, 16, 16);
                secrets.add(key);
                return key;
            }
            case "credential" -> {
                String[] keys = {"password", "secret", "token", "api_key", "db_password", "client_secret"};
                String key = keys[r.nextInt(keys.length)];
                String value = chars(r, VALUE, 8, 16);
                secrets.add(value);
                return switch (r.nextInt(3)) {
                    case 0 -> key + "=" + value;
                    case 1 -> key + ": " + value;
                    default -> "\"" + key + "\": \"" + value + "\"";
                };
            }
            case "email" -> {
                String[] tlds = {"com", "org", "io", "dev"};
                String email = chars(r, "abcdefghijklmnopqrstuvwxyz", 1, 1) + chars(r, "abcdefghij0123456789._", 2, 9)
                        + "@" + chars(r, "abcdefghijklmnopqrstuvwxyz", 3, 8) + "." + tlds[r.nextInt(tlds.length)];
                secrets.add(email);
                return email;
            }
            default -> {
                String digits = luhnDigits(r, 13 + r.nextInt(7));
                secrets.add(digits);
                String formatted = r.nextBoolean() ? digits : group(digits, r.nextBoolean() ? ' ' : '-');
                secrets.add(formatted);
                return formatted;
            }
        }
    }

    private static String chars(SplittableRandom r, String alphabet, int min, int max) {
        int length = min + r.nextInt(max - min + 1);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static String luhnDigits(SplittableRandom r, int length) {
        int[] digits = randomDigits(r, length);
        digits[length - 1] = 0;
        int check = (10 - luhnSum(digits) % 10) % 10;
        digits[length - 1] = check;
        return join(digits);
    }

    private static String nonLuhnDigits(SplittableRandom r) {
        int length = 13 + r.nextInt(7);
        int[] digits = randomDigits(r, length);
        digits[length - 1] = 0;
        int check = (10 - luhnSum(digits) % 10) % 10;
        digits[length - 1] = (check + 1 + r.nextInt(9)) % 10;
        return join(digits);
    }

    private static int[] randomDigits(SplittableRandom r, int length) {
        int[] digits = new int[length];
        digits[0] = 1 + r.nextInt(9);
        for (int i = 1; i < length; i++) {
            digits[i] = r.nextInt(10);
        }
        return digits;
    }

    private static int luhnSum(int[] digits) {
        int sum = 0;
        for (int k = 0; k < digits.length; k++) {
            int d = digits[digits.length - 1 - k];
            if (k % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum;
    }

    private static String join(int[] digits) {
        StringBuilder out = new StringBuilder(digits.length);
        for (int d : digits) {
            out.append(d);
        }
        return out.toString();
    }

    private static String group(String digits, char separator) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(separator);
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> map) {
        return (Map<String, Object>) copy(map);
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k, copy(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(copy(v)));
            return out;
        }
        return value;
    }

    private static List<String> strings(Object value, List<String> out) {
        if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> strings(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> strings(v, out));
        }
        return out;
    }
}
