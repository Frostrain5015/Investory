package com.investory.crawler;

import java.util.Locale;
import java.util.Set;

/** Suppress provider inline reasoning tags, even when split across stream frames. */
public final class PublicAnswerFilter {
    private static final Set<String> TAGS = Set.of("<think>", "</think>", "<thinking>", "</thinking>");
    private final StringBuilder pending = new StringBuilder();
    private boolean hidden;

    public String accept(String chunk) {
        StringBuilder visible = new StringBuilder();
        for (char character : chunk.toCharArray()) {
            if (!pending.isEmpty() || character == '<') {
                pending.append(character);
                String candidate = pending.toString().replaceAll("\\s", "").toLowerCase(Locale.ROOT);
                if (TAGS.contains(candidate)) {
                    hidden = !candidate.startsWith("</");
                    pending.setLength(0);
                } else if (pending.length() > 64 || TAGS.stream().noneMatch(tag -> tag.startsWith(candidate))) {
                    if (!hidden) visible.append(pending);
                    pending.setLength(0);
                }
            } else if (!hidden) {
                visible.append(character);
            }
        }
        return visible.toString();
    }

    public static String sanitize(String text) {
        return new PublicAnswerFilter().accept(text == null ? "" : text);
    }
}
