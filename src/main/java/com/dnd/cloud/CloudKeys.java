package com.dnd.cloud;

import java.util.Locale;

/**
 * Turns user-supplied strings (usernames, emails) into case-insensitive keys that are legal
 * Firebase node names and safe inside a URL path. Letters, digits and '-' are kept; every
 * other character becomes {@code _<hex>_}, so the mapping can't collide.
 */
public final class CloudKeys {
    private CloudKeys() {
    }

    public static String of(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        lower.codePoints().forEach(cp -> {
            if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '-') {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_').append(Integer.toHexString(cp)).append('_');
            }
        });
        return sb.toString();
    }
}
