package com.dnd.ui.components;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class DisplayOrder {
    private DisplayOrder() { }

    public static <T> List<T> named(List<T> values, Function<T, String> label) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull)
            .sorted(Comparator.comparing(label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }
}
