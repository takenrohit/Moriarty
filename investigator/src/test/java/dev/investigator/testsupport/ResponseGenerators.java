package dev.investigator.testsupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Seeded generators for backend response bodies used by the Phase 2 parsing properties (design "Correctness
 * Properties"). Each method takes the property's {@link SplittableRandom}, so a seed fixes the body and the
 * expected parse result.
 *
 * <p>Prometheus: {@link #prometheusMatrix} builds a {@code query_range} matrix body with a single series, as
 * the client requires (CP-61). Loki, Tempo and GitHub generators are added by later tasks.
 */
public final class ResponseGenerators {

    /** Unix seconds of the demo alert time (2026-11-23T14:40:00Z); sample times are generated around it. */
    private static final long ANCHOR_SECONDS = DomainGenerators.ANCHOR.getEpochSecond();

    private static final String[] NON_FINITE = {"NaN", "+Inf", "-Inf"};
    private static final String[] LABEL_NAMES = {"service", "status", "instance", "job", "le"};

    private ResponseGenerators() {
    }

    /**
     * One {@code [unix_seconds, "value"]} sample as written in the body, with the parsed form a correct
     * client must produce.
     *
     * @param at the sample time the timestamp text denotes (millisecond precision)
     * @param timeText the timestamp as a JSON number: an integer, a decimal, or scientific notation
     * @param valueText the value string: an integer, a decimal, scientific notation, {@code NaN} or {@code ±Inf}
     * @param value the number {@code valueText} denotes; {@code NaN} or infinite for the non-finite forms
     */
    public record PromSample(Instant at, String timeText, String valueText, double value) {

        public boolean finite() {
            return Double.isFinite(value);
        }
    }

    /**
     * A {@code query_range} success body and the samples it contains, in body order.
     *
     * @param body the JSON response body
     * @param samples every sample in the body, in the order written (not necessarily time order)
     */
    public record PromMatrix(String body, List<PromSample> samples) {

        public PromMatrix {
            samples = List.copyOf(samples);
        }
    }

    /**
     * A Prometheus {@code query_range} matrix body with one series.
     *
     * <p>The sample count is 0, 1, {@code maxPoints − 1}, {@code maxPoints}, {@code maxPoints + 1} or random
     * in {@code [0, maxPoints + 20]}. Timestamps are distinct, millisecond precision, usually ascending
     * (as Prometheus sends them) and shuffled in about 1 case in 5. In about half the cases every value is
     * finite; otherwise about 1 sample in 8 is {@code NaN}, {@code +Inf} or {@code -Inf}.
     */
    public static PromMatrix prometheusMatrix(SplittableRandom r, int maxPoints) {
        int count = sampleCount(r, maxPoints);
        boolean withNonFinite = r.nextBoolean();
        List<PromSample> samples = new ArrayList<>(count);
        long millis = (ANCHOR_SECONDS - r.nextLong(0, 86_400)) * 1000 + r.nextInt(1000);
        for (int i = 0; i < count; i++) {
            millis += 1 + r.nextLong(0, 60_000);
            samples.add(sample(r, millis, withNonFinite && r.nextInt(8) == 0));
        }
        if (r.nextInt(5) == 0) {
            Collections.shuffle(samples, new Random(r.nextLong()));
        }

        StringBuilder body = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",")
                .append("\"result\":[{\"metric\":").append(labels(r)).append(",\"values\":[");
        for (int i = 0; i < samples.size(); i++) {
            PromSample s = samples.get(i);
            body.append(i == 0 ? "" : ",").append('[').append(s.timeText()).append(",\"").append(s.valueText())
                    .append("\"]");
        }
        body.append("]}]}}");
        return new PromMatrix(body.toString(), samples);
    }

    private static int sampleCount(SplittableRandom r, int maxPoints) {
        return switch (r.nextInt(6)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> maxPoints - 1;
            case 3 -> maxPoints;
            case 4 -> maxPoints + 1;
            default -> r.nextInt(maxPoints + 21);
        };
    }

    private static PromSample sample(SplittableRandom r, long epochMillis, boolean nonFinite) {
        Instant at = Instant.ofEpochMilli(epochMillis);
        BigDecimal seconds = BigDecimal.valueOf(epochMillis, 3);
        String timeText = switch (r.nextInt(3)) {
            // Whole seconds are written as an integer when the fraction is zero, as Prometheus does.
            case 0 -> seconds.stripTrailingZeros().toPlainString();
            case 1 -> seconds.toPlainString();
            default -> seconds.movePointLeft(9).toPlainString() + "E9";
        };
        if (nonFinite) {
            String text = NON_FINITE[r.nextInt(NON_FINITE.length)];
            double value = switch (text) {
                case "+Inf" -> Double.POSITIVE_INFINITY;
                case "-Inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.NaN;
            };
            return new PromSample(at, timeText, text, value);
        }
        return switch (r.nextInt(4)) {
            case 0 -> {
                long whole = r.nextLong(-1_000_000, 1_000_001);
                yield new PromSample(at, timeText, Long.toString(whole), whole);
            }
            case 1 -> {
                double d = (r.nextBoolean() ? 1 : -1) * r.nextDouble() * Math.pow(10, r.nextInt(-12, 13));
                yield new PromSample(at, timeText, Double.toString(d), d);
            }
            case 2 -> {
                // Go-style scientific notation, e.g. 1.234e-07.
                BigDecimal mantissa = BigDecimal.valueOf(r.nextInt(-9999, 10_000), 3);
                int exponent = r.nextInt(-20, 21);
                String text = mantissa.toPlainString() + "e" + (exponent < 0 ? "-" : "+")
                        + String.format("%02d", Math.abs(exponent));
                yield new PromSample(at, timeText, text, mantissa.scaleByPowerOfTen(exponent).doubleValue());
            }
            default -> {
                String[] edges = {"0", "1", "0.5", Double.toString(Double.MAX_VALUE),
                        Double.toString(-Double.MAX_VALUE), Double.toString(Double.MIN_VALUE), "1e308", "2.5E-300"};
                String text = edges[r.nextInt(edges.length)];
                yield new PromSample(at, timeText, text, new BigDecimal(text).doubleValue());
            }
        };
    }

    /** {@code {}} (an aggregated series) or up to three safe {@code name: value} labels. */
    private static String labels(SplittableRandom r) {
        int n = r.nextInt(2) == 0 ? 0 : r.nextInt(1, 4);
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(LABEL_NAMES[i]).append("\":\"v")
                    .append(r.nextInt(1000)).append('"');
        }
        return json.append('}').toString();
    }
}
