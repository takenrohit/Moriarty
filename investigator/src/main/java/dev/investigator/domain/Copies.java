package dev.investigator.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defensive-copy helpers for record compact constructors (Req 3.3).
 *
 * <p>A null collection becomes an empty immutable one; a non-null collection is copied into an
 * immutable one. Maps and sets keep their iteration order (unlike {@code Map.copyOf} and
 * {@code Set.copyOf}), so JSON output and rendering stay deterministic.
 */
final class Copies {

    private Copies() {
    }

    static <T> List<T> list(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    static <K, V> Map<K, V> map(Map<K, V> source) {
        return source == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <T> Set<T> set(Set<T> source) {
        return source == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
