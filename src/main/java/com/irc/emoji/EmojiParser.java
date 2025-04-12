package com.irc.emoji;

import java.util.ArrayList;
import java.util.List;

public class EmojiParser {
    public enum FitzpatrickAction {
        PARSE, REMOVE, IGNORE
    }

    public static String parseToAliases(String input) {
        return parseToAliases(input, FitzpatrickAction.PARSE);
    }

    public static String parseToAliases(String input, FitzpatrickAction fitzpatrickAction) {
        if (input == null || input.isEmpty()) return input;

        int prev = 0;
        StringBuilder sb = new StringBuilder(input.length());
        List<UnicodeCandidate> candidates = getUnicodeCandidates(input);

        for (UnicodeCandidate candidate : candidates) {
            sb.append(input, prev, candidate.getEmojiStartIndex());

            String replacement;
            switch (fitzpatrickAction) {
                case PARSE:
                    if (candidate.hasFitzpatrick()) {
                        replacement = ":" +
                                candidate.getEmoji().getAliases().get(0) +
                                "|" +
                                candidate.getFitzpatrickType() +
                                ":";
                    } else {
                        replacement = ":" + candidate.getEmoji().getAliases().get(0) + ":";
                    }
                    break;
                case REMOVE:
                    replacement = ":" + candidate.getEmoji().getAliases().get(0) + ":";
                    break;
                case IGNORE:
                default:
                    replacement = ":" +
                            candidate.getEmoji().getAliases().get(0) +
                            ":" +
                            candidate.getFitzpatrickUnicode();
                    break;
            }

            sb.append(replacement);
            prev = candidate.getFitzpatrickEndIndex();
        }

        return sb.append(input.substring(prev)).toString();
    }

    /**
     * Generates a list of UnicodeCandidate found in the input string.
     */
    protected static List<UnicodeCandidate> getUnicodeCandidates(String input) {
        char[] inputCharArray = input.toCharArray();
        List<UnicodeCandidate> candidates = new ArrayList<>();
        UnicodeCandidate next;

        for (int i = 0; (next = getNextUnicodeCandidate(inputCharArray, i)) != null;
             i = next.getFitzpatrickEndIndex()) {
            candidates.add(next);
        }

        return candidates;
    }

    /**
     * Finds the next UnicodeCandidate after a given starting index
     */
    protected static UnicodeCandidate getNextUnicodeCandidate(char[] chars, int start) {
        for (int i = start; i < chars.length; i++) {
            int emojiEnd = EmojiManager.getEmojiEndPos(chars, i);

            if (emojiEnd != -1) {
                Emoji emoji = EmojiManager.getByUnicode(new String(chars, i, emojiEnd - i));
                String fitzpatrickString = (emojiEnd + 2 <= chars.length) ?
                        new String(chars, emojiEnd, 2) :
                        null;
                return new UnicodeCandidate(emoji, fitzpatrickString, i);
            }
        }

        return null;
    }

    public static class UnicodeCandidate {
        private final Emoji emoji;
        private final Fitzpatrick fitzpatrick;
        private final int startIndex;

        private UnicodeCandidate(Emoji emoji, String fitzpatrick, int startIndex) {
            this.emoji = emoji;
            this.fitzpatrick = Fitzpatrick.fromUnicode(fitzpatrick);
            this.startIndex = startIndex;
        }

        public Emoji getEmoji() {
            return emoji;
        }

        public boolean hasFitzpatrick() {
            return getFitzpatrick() != null;
        }

        public Fitzpatrick getFitzpatrick() {
            return fitzpatrick;
        }

        public String getFitzpatrickType() {
            return hasFitzpatrick() ? fitzpatrick.name().toLowerCase() : "";
        }

        public String getFitzpatrickUnicode() {
            return hasFitzpatrick() ? fitzpatrick.unicode : "";
        }

        public int getEmojiStartIndex() {
            return startIndex;
        }

        public int getEmojiEndIndex() {
            return startIndex + emoji.getUnicode().length();
        }

        public int getFitzpatrickEndIndex() {
            return getEmojiEndIndex() + (fitzpatrick != null ? 2 : 0);
        }
    }
}