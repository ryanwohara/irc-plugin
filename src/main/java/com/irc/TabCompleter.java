package com.irc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Tab completion for the input box: the word before the caret is completed from the nicks in
 * the focused buffer, or from the joined channels when it starts with '#'.
 *
 * Pressing Tab again straight away cycles through the other matches. That is detected by the
 * input still being exactly what the last completion produced; any edit, history recall or
 * caret move in between starts a fresh completion instead.
 */
class TabCompleter {

    /** The input box contents after a completion. */
    static final class Result {
        final String text;
        final int caret;

        Result(String text, int caret) {
            this.text = text;
            this.caret = caret;
        }
    }

    private List<String> matches = new ArrayList<>();
    private int index;
    /** The word the user typed before the first Tab. */
    private String word = "";
    private String head = "";
    private String tail = "";
    private boolean lineStart;
    private String lastText;
    private int lastCaret = -1;

    /**
     * Completes or cycles. Returns null when there is nothing to complete, leaving the input as it is.
     *
     * @param forward false steps backwards through the matches (Shift+Tab)
     */
    Result complete(String text, int caret, Collection<String> nicks, Collection<String> channels, boolean forward) {
        if (text.equals(lastText) && caret == lastCaret && !matches.isEmpty()) {
            // The input box is shared across buffers, so the candidates may have changed since the
            // last Tab. If the matches for the original word differ, start over with the new ones.
            List<String> current = findMatches(word, word.startsWith("#") ? channels : nicks);
            if (current.equals(matches)) {
                index = Math.floorMod(index + (forward ? 1 : -1), matches.size());
            } else if (current.isEmpty()) {
                matches = current;
                lastText = null;
                return null;
            } else {
                matches = current;
                index = forward ? 0 : matches.size() - 1;
            }
        } else {
            int wordStart = caret;
            while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
                wordStart--;
            }
            word = text.substring(wordStart, caret);
            if (word.isEmpty()) {
                return null;
            }
            matches = findMatches(word, word.startsWith("#") ? channels : nicks);
            if (matches.isEmpty()) {
                lastText = null;
                return null;
            }
            head = text.substring(0, wordStart);
            tail = text.substring(caret);
            lineStart = head.trim().isEmpty();
            index = forward ? 0 : matches.size() - 1;
        }

        String match = matches.get(index);
        // "bob: " addresses someone at the start of a line; elsewhere, and for channels, just a space.
        String suffix = lineStart && !match.startsWith("#") ? ": " : " ";
        if (tail.startsWith(" ")) {
            suffix = suffix.substring(0, suffix.length() - 1);
        }
        String completed = head + match + suffix;
        lastText = completed + tail;
        lastCaret = completed.length();
        return new Result(lastText, lastCaret);
    }

    private static List<String> findMatches(String word, Collection<String> candidates) {
        String lowerWord = word.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lowerWord) && !found.contains(candidate)) {
                found.add(candidate);
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        return found;
    }
}
