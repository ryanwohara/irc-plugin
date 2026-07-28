package com.irc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's mode vocabulary, learned from RPL_ISUPPORT (numeric 005).
 *
 * Two tokens matter. {@code PREFIX=(qaohv)~&@%+} positionally pairs membership mode letters with
 * their display prefixes, and the position doubles as the rank (0 = most privileged).
 * {@code CHANMODES=A,B,C,D} says which mode letters consume a parameter, which is what makes
 * {@code MODE #chan +mo bob} apply "o" to "bob" rather than to whatever came next.
 *
 * Falls back to the widely-supported defaults below when a server sends no 005, or sends one
 * that omits or mangles a token. Mode letters absent from both tables are assumed parameterless.
 */
class ModeSpec {

    private static final String DEFAULT_PREFIX = "(qaohv)~&@%+";
    private static final String DEFAULT_CHANMODES = "beI,k,l,imnpst";

    private final Map<Character, Character> prefixByMode = new HashMap<>();
    private final Map<Character, Character> modeByPrefix = new HashMap<>();
    private final Map<Character, Integer> rankByMode = new HashMap<>();

    private String typeA = "";
    private String typeB = "";
    private String typeC = "";

    static ModeSpec defaults() {
        ModeSpec spec = new ModeSpec();
        spec.parsePrefix(DEFAULT_PREFIX);
        spec.parseChanmodes(DEFAULT_CHANMODES);
        return spec;
    }

    /**
     * Applies the token list from a 005 line. The leading nick and the trailing human-readable
     * description are not {@code KEY=VALUE} pairs and are skipped, as is any token we do not use.
     */
    void applyIsupport(List<String> tokens) {
        if (tokens == null) {
            return;
        }
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = token.substring(0, equals);
            String value = token.substring(equals + 1);
            if ("PREFIX".equals(key)) {
                parsePrefix(value);
            } else if ("CHANMODES".equals(key)) {
                parseChanmodes(value);
            }
        }
    }

    /** Parses {@code (modes)prefixes}; leaves the current table untouched if it does not parse. */
    private void parsePrefix(String value) {
        int close = value.indexOf(')');
        if (!value.startsWith("(") || close < 0) {
            return;
        }
        String modes = value.substring(1, close);
        String prefixes = value.substring(close + 1);
        if (modes.length() != prefixes.length() || modes.isEmpty()) {
            return;
        }

        prefixByMode.clear();
        modeByPrefix.clear();
        rankByMode.clear();
        for (int i = 0; i < modes.length(); i++) {
            prefixByMode.put(modes.charAt(i), prefixes.charAt(i));
            modeByPrefix.put(prefixes.charAt(i), modes.charAt(i));
            rankByMode.put(modes.charAt(i), i);
        }
    }

    /** Parses {@code A,B,C,D}. Type D is not stored - it is the "no parameter" default. */
    private void parseChanmodes(String value) {
        String[] groups = value.split(",", -1);
        typeA = groups.length > 0 ? groups[0] : "";
        typeB = groups.length > 1 ? groups[1] : "";
        typeC = groups.length > 2 ? groups[2] : "";
    }

    boolean isPrefixMode(char modeLetter) {
        return prefixByMode.containsKey(modeLetter);
    }

    char prefixFor(char modeLetter) {
        Character prefix = prefixByMode.get(modeLetter);
        return prefix == null ? '\0' : prefix;
    }

    Character modeForPrefix(char prefixChar) {
        return modeByPrefix.get(prefixChar);
    }

    int rankOf(char modeLetter) {
        Integer rank = rankByMode.get(modeLetter);
        return rank == null ? Integer.MAX_VALUE : rank;
    }

    boolean takesParameter(char modeLetter, boolean adding) {
        if (isPrefixMode(modeLetter)) {
            return true;
        }
        if (typeA.indexOf(modeLetter) >= 0 || typeB.indexOf(modeLetter) >= 0) {
            return true;
        }
        if (typeC.indexOf(modeLetter) >= 0) {
            return adding;
        }
        return false;
    }
}
