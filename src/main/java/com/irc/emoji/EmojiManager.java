package com.irc.emoji;

import java.util.*;

public class EmojiManager {
    private static final Map<String, Emoji> EMOJI_BY_UNICODE = new HashMap<>();
    private static final Map<String, Emoji> EMOJI_BY_ALIAS = new HashMap<>();
    private static final List<Emoji> ALL_EMOJIS = new ArrayList<>();
    private static EmojiTrie EMOJI_TRIE;
    private static boolean initialized = false;

    static boolean isInitialized() {
        return initialized;
    }

    static void register(Emoji emoji) {
        EMOJI_BY_UNICODE.put(emoji.getUnicode(), emoji);
        ALL_EMOJIS.add(emoji);
        for (String alias : emoji.getAliases()) {
            EMOJI_BY_ALIAS.put(alias, emoji);
        }
    }

    static void finishInitialization() {
        EMOJI_TRIE = new EmojiTrie(ALL_EMOJIS);
        initialized = true;
    }

    public static Emoji getByUnicode(String unicode) {
        if (unicode == null) return null;
        return EMOJI_TRIE.getEmoji(unicode);
    }

    public static int getEmojiEndPos(char[] text, int startPos) {
        if (!initialized) {
            throw new IllegalStateException("EmojiManager has not been initialized. Call initialize() first.");
        }

        int best = -1;
        for (int j = startPos + 1; j <= text.length; j++) {
            EmojiTrie.Matches status = EMOJI_TRIE.isEmoji(text, startPos, j);
            if (status.exactMatch()) {
                best = j;
            } else if (status.impossibleMatch()) {
                return best;
            }
        }
        return best;
    }
}