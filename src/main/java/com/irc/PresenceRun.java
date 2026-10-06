package com.irc;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ColorUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * A run of joins, parts, quits, kicks and nick changes, grouped by kind for the pop-out's one-line
 * summary: {@code Joins: alice, gina · Parts: carol · Quits: bob · Nicks: dave → dave_ · Kicks: frank (by eve)}.
 *
 * Each nick appears once per group, in the order first seen, so someone hopping in and out shows
 * once under Joins and once under Parts. Nick changes are the exception: every one is listed.
 * Reasons are dropped.
 *
 * The nicks are read back out of the lines {@link IrcAdapter} builds; a line that doesn't match
 * its wording isn't treated as presence at all, so it keeps its own line.
 */
final class PresenceRun {

    private static final String JOINED = " has joined.";
    private static final String PARTED = " parted";
    private static final String QUIT = " quit";
    private static final String RENAMED = " is now known as";
    private static final String KICKED = " kicked ";

    private static final String JOIN_COLOR = ColorUtil.toHexColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
    private static final String LEAVE_COLOR = ColorUtil.toHexColor(ColorScheme.PROGRESS_ERROR_COLOR);
    private static final String CHANGE_COLOR = ColorUtil.toHexColor(ColorScheme.BRAND_ORANGE);

    /** Nick by folded nick, so the first spelling seen is the one shown. */
    private final Map<String, String> joins = new LinkedHashMap<>();
    private final Map<String, String> parts = new LinkedHashMap<>();
    private final Map<String, String> quits = new LinkedHashMap<>();
    private final List<String[]> nicks = new ArrayList<>();
    /** {kicked, kicker} by folded kicked nick; a later kick replaces the kicker but not the position. */
    private final Map<String, String[]> kicks = new LinkedHashMap<>();

    /** Whether {@code message} is a join, part, quit, kick or nick change this class can read. */
    static boolean isPresence(IrcMessage message) {
        String sender = message.getSender();
        String content = message.getContent();
        if (sender == null) return false;
        switch (message.getType()) {
            case JOIN:
                return content != null && content.endsWith(JOINED) && content.length() > JOINED.length();
            case PART:
                return sender.endsWith(PARTED) && sender.length() > PARTED.length();
            case QUIT:
                return sender.endsWith(QUIT) && sender.length() > QUIT.length();
            case NICK_CHANGE:
                return sender.endsWith(RENAMED) && sender.length() > RENAMED.length()
                        && content != null && !content.isEmpty();
            case KICK:
                return sender.indexOf(KICKED) > 0 && !sender.endsWith(KICKED);
            default:
                return false;
        }
    }

    /** Adds one event; call only with messages {@link #isPresence} accepts. */
    void add(IrcMessage message) {
        String sender = message.getSender();
        String content = message.getContent();
        switch (message.getType()) {
            case JOIN:
                once(joins, content.substring(0, content.length() - JOINED.length()));
                break;
            case PART:
                once(parts, sender.substring(0, sender.length() - PARTED.length()));
                break;
            case QUIT:
                once(quits, sender.substring(0, sender.length() - QUIT.length()));
                break;
            case NICK_CHANGE:
                nicks.add(new String[]{sender.substring(0, sender.length() - RENAMED.length()), content});
                break;
            case KICK: {
                int at = sender.indexOf(KICKED);
                String kicked = sender.substring(at + KICKED.length());
                kicks.put(fold(kicked), new String[]{kicked, sender.substring(0, at)});
                break;
            }
            default:
                throw new IllegalArgumentException("Not a presence line: " + message.getType());
        }
    }

    /** The groups, each in its usual colour; {@code nick} turns one nick into HTML. */
    String toHtml(Function<String, String> nick) {
        List<String> groups = new ArrayList<>();
        group(groups, "Joins", JOIN_COLOR, names(joins.values(), nick));
        group(groups, "Parts", LEAVE_COLOR, names(parts.values(), nick));
        group(groups, "Quits", LEAVE_COLOR, names(quits.values(), nick));
        List<String> renames = new ArrayList<>();
        for (String[] change : nicks) renames.add(nick.apply(change[0]) + " → " + nick.apply(change[1]));
        group(groups, "Nicks", CHANGE_COLOR, renames);
        List<String> kicked = new ArrayList<>();
        for (String[] kick : kicks.values()) kicked.add(nick.apply(kick[0]) + " (by " + nick.apply(kick[1]) + ")");
        group(groups, "Kicks", CHANGE_COLOR, kicked);
        return String.join(" · ", groups);
    }

    private static void group(List<String> groups, String label, String color, List<String> items) {
        if (items.isEmpty()) return;
        groups.add("<font style=\"color:" + color + "\">" + label + ": " + String.join(", ", items) + "</font>");
    }

    private static List<String> names(Iterable<String> nicks, Function<String, String> nick) {
        List<String> names = new ArrayList<>();
        for (String name : nicks) names.add(nick.apply(name));
        return names;
    }

    private static void once(Map<String, String> group, String nick) {
        group.putIfAbsent(fold(nick), nick);
    }

    private static String fold(String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }
}
