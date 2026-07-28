package com.irc;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is in each channel and what membership modes they hold.
 *
 * Written from the socket reader thread and read from the Swing EDT, so every method is
 * synchronized and {@link #snapshot} hands back an unmodifiable copy rather than a live view.
 *
 * Channels and nicks are keyed by their ASCII-lowercased form because IRC treats them
 * case-insensitively, but the server's casing is preserved for display.
 */
class ChannelUserList {

    /** One user as the UI should draw them: display nick, highest prefix, and that prefix's rank. */
    @Value
    static class Entry {
        String nick;
        String prefix;
        int rank;
    }

    private static final class User {
        private String displayNick;
        private final Set<Character> modes = new LinkedHashSet<>();

        User(String displayNick) {
            this.displayNick = displayNick;
        }
    }

    private static final class Channel {
        private final String displayName;
        private final Map<String, User> users = new HashMap<>();

        Channel(String displayName) {
            this.displayName = displayName;
        }
    }

    private final ModeSpec spec;
    private final Map<String, Channel> live = new HashMap<>();
    private final Map<String, Channel> pending = new HashMap<>();

    ChannelUserList(ModeSpec spec) {
        this.spec = spec;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Accumulates one 353 line into the pending roster. Leading characters are consumed while
     * they are known prefixes, so "@+bob" yields both modes.
     */
    synchronized void addNames(String channel, List<String> rawEntries) {
        if (rawEntries == null) {
            return;
        }
        Channel buffer = pending.computeIfAbsent(key(channel), k -> new Channel(channel));
        for (String raw : rawEntries) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            Set<Character> modes = new LinkedHashSet<>();
            int i = 0;
            while (i < raw.length()) {
                Character mode = spec.modeForPrefix(raw.charAt(i));
                if (mode == null) {
                    break;
                }
                modes.add(mode);
                i++;
            }
            String nick = raw.substring(i);
            if (nick.isEmpty()) {
                continue;
            }
            User user = buffer.users.computeIfAbsent(key(nick), k -> new User(nick));
            user.displayNick = nick;
            user.modes.addAll(modes);
        }
    }

    /** Commits the pending roster over the live one (366). Replaces rather than merges. */
    synchronized void endNames(String channel) {
        Channel buffer = pending.remove(key(channel));
        if (buffer != null) {
            live.put(key(channel), buffer);
        }
    }

    synchronized void join(String channel, String nick) {
        live.computeIfAbsent(key(channel), k -> new Channel(channel))
                .users.computeIfAbsent(key(nick), k -> new User(nick));
    }

    synchronized void part(String channel, String nick) {
        Channel ch = live.get(key(channel));
        if (ch != null) {
            ch.users.remove(key(nick));
        }
    }

    synchronized void kick(String channel, String nick) {
        part(channel, nick);
    }

    /** Removes the nick everywhere; returns the display names of the channels that changed. */
    synchronized List<String> quit(String nick) {
        List<String> affected = new ArrayList<>();
        for (Channel ch : live.values()) {
            if (ch.users.remove(key(nick)) != null) {
                affected.add(ch.displayName);
            }
        }
        return affected;
    }

    /** Re-keys the nick everywhere, keeping modes; returns the channels that changed. */
    synchronized List<String> rename(String oldNick, String newNick) {
        List<String> affected = new ArrayList<>();
        for (Channel ch : live.values()) {
            User user = ch.users.remove(key(oldNick));
            if (user == null) {
                continue;
            }
            user.displayNick = newNick;
            ch.users.put(key(newNick), user);
            affected.add(ch.displayName);
        }
        return affected;
    }

    /**
     * Applies a MODE change. {@code modeParams} is the MODE command's parameters minus the
     * target, e.g. {@code ["+mo", "bob"]}.
     *
     * Every letter that {@link ModeSpec#takesParameter} reports consumes one is consumed even
     * when we then discard it - that consumption is what keeps the remaining parameters aligned
     * with the remaining membership modes.
     */
    synchronized void applyModeChange(String channel, List<String> modeParams) {
        if (modeParams == null || modeParams.isEmpty()) {
            return;
        }
        Channel ch = live.get(key(channel));
        if (ch == null) {
            return;
        }

        String modeString = modeParams.get(0);
        int paramIndex = 1;
        boolean adding = true;

        for (int i = 0; i < modeString.length(); i++) {
            char letter = modeString.charAt(i);
            if (letter == '+') {
                adding = true;
                continue;
            }
            if (letter == '-') {
                adding = false;
                continue;
            }

            String param = null;
            if (spec.takesParameter(letter, adding)) {
                if (paramIndex >= modeParams.size()) {
                    return; // malformed: stop rather than misapply what is left
                }
                param = modeParams.get(paramIndex++);
            }

            if (param == null || !spec.isPrefixMode(letter)) {
                continue;
            }
            User user = ch.users.get(key(param));
            if (user == null) {
                continue;
            }
            if (adding) {
                user.modes.add(letter);
            } else {
                user.modes.remove(letter);
            }
        }
    }

    synchronized void removeChannel(String channel) {
        live.remove(key(channel));
        pending.remove(key(channel));
    }

    synchronized void clear() {
        live.clear();
        pending.clear();
    }

    /** Users sorted by rank then case-insensitive nick. Unmodifiable. */
    synchronized List<Entry> snapshot(String channel) {
        Channel ch = live.get(key(channel));
        if (ch == null) {
            return Collections.emptyList();
        }

        List<Entry> entries = new ArrayList<>(ch.users.size());
        for (User user : ch.users.values()) {
            char bestMode = '\0';
            int bestRank = Integer.MAX_VALUE;
            for (char mode : user.modes) {
                int rank = spec.rankOf(mode);
                if (rank < bestRank) {
                    bestRank = rank;
                    bestMode = mode;
                }
            }
            char prefixChar = bestMode == '\0' ? '\0' : spec.prefixFor(bestMode);
            String prefix = prefixChar == '\0' ? "" : String.valueOf(prefixChar);
            entries.add(new Entry(user.displayNick, prefix, bestRank));
        }

        entries.sort(Comparator.comparingInt(Entry::getRank)
                .thenComparing(Entry::getNick, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(entries);
    }
}
