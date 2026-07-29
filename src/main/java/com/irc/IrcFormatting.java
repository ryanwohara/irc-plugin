package com.irc;

import java.util.regex.Pattern;

/**
 * IRC formatting control codes, shared by every surface that needs message text as plain text.
 *
 * The background group is {@code ,\d\d?}, not {@code ,\d\d}: a single-digit background is legal and
 * the renderer in {@link IrcPanel.ChannelPane} already parses it as one. Requiring two digits here
 * made the stripper disagree with the renderer and leave a stray ",5" in the output.
 *
 * Written with \xNN escapes rather than literal control characters so the source stays readable.
 */
public final class IrcFormatting {
    /** Bold, colour (optional 1-2 digit foreground and background), italic, reverse, reset. */
    private static final Pattern STRIP_CODES =
            Pattern.compile("\\x02|\\x03(?:\\d\\d?(?:,\\d\\d?)?)?|\\x1D|\\x15|\\x0F");

    private IrcFormatting() {
    }

    /** Returns {@code text} with all formatting codes removed. Null and empty pass through. */
    public static String stripCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return STRIP_CODES.matcher(text).replaceAll("");
    }
}
