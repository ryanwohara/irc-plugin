# Nicklist and User Mode Tracking Design

**Date:** 2026-07-28
**Status:** Approved

## Summary

Add a channel nicklist to the IRC side panel and track each user's per-channel status
(owner/admin/op/halfop/voice). The nicklist is a compact `JComboBox` placed immediately left of
the existing buffer dropdown; selecting a nick opens (or focuses) a private-message buffer with
that user.

## Requirements

- Track, per channel, every user present and the set of prefix modes the server has granted them.
- Keep that state correct across `NAMES`, `JOIN`, `PART`, `QUIT`, `KICK`, `NICK`, and `MODE`.
- Display the current channel's users in a dropdown, sorted by rank then alphabetically, each
  rendered with its highest prefix character (`~ & @ % +`) and colored the same as in chat.
- Selecting a user opens a PM buffer with them and focuses it.
- The dropdown is disabled and reads `Users (0)` on non-channel buffers (System, PMs).
- Existing behavior is preserved: `/names` still prints the raw name list to the chat pane, and
  channel-wide `MODE` changes still print to the chat pane.
- Degrade gracefully: a server that sends no `005`/ISUPPORT still gets a working nicklist via
  hardcoded fallbacks.

## Motivating Constraint

RuneLite `PluginPanel` content is roughly 225px wide. A HexChat-style side-by-side nicklist would
leave ~140px for chat and ~80px for names, so both columns would truncate constantly. A dropdown
in the existing `row2` control strip costs zero chat space and zero vertical space.

## Current State (for reference)

| Location | Today | Problem |
|---|---|---|
| `SimpleIrcClient:50` | `Map<String, Set<String>> channelUsers` | Bare nicks with the prefix **stripped**; unsynchronized `HashMap`/`HashSet` mutated on the socket reader thread; no getter |
| `SimpleIrcClient:32` | `USER_PREFIXES = ^[~&@%+].+` | Hardcoded; strips exactly one prefix char, so multi-prefix (`@+bob`) is mishandled |
| `SimpleIrcClient` numerics | No `005` case, no `366` case | Server's `PREFIX=` / `CHANMODES=` are unknown |
| `SimpleIrcClient:439` `MODE` | Stringified and fired for display only | Never applied to user state |
| `IrcAdapter:23` | A second `Map<String, Set<String>> channelUsers` | Declared, never read or written — dead |

### Why ISUPPORT parsing is mandatory, not optional

Applying `MODE #chan +mo bob` correctly requires knowing that `m` consumes no parameter and `o`
consumes one. Without that, the mode letters and the parameter list misalign and the wrong user
gets opped. `PREFIX=` and `CHANMODES=` from `005` are the only authoritative source, so parsing
them is part of any correct implementation.

## Architecture

Two new pure classes — no Swing, no socket, fully unit-testable — owned by `SimpleIrcClient`.

### `ModeSpec`

Parses and answers questions about the server's mode vocabulary.

**`PREFIX=(qaohv)~&@%+`** — position *i* in the parenthesized group is the mode letter whose prefix
character is at position *i* of the trailing group. Position also defines rank, `0` = highest.

**`CHANMODES=beI,k,l,imnpst`** — four comma-separated groups:

| Type | Example | Parameter when adding | Parameter when removing |
|---|---|---|---|
| A (lists) | `b e I` | yes | yes |
| B (settings) | `k` | yes | yes |
| C (set-only) | `l` | yes | no |
| D (flags) | `i m n p s t` | no | no |

Prefix modes (`o`, `v`, …) are **not** listed in `CHANMODES` and always consume a parameter.

**API**

```java
final class ModeSpec {
    static ModeSpec defaults();
    void applyIsupport(List<String> tokens);       // ignores unparseable tokens
    boolean isPrefixMode(char modeLetter);
    char prefixFor(char modeLetter);               // '\0' when not a prefix mode
    Character modeForPrefix(char prefixChar);      // null when not a prefix char
    int rankOf(char modeLetter);                   // 0 = highest, Integer.MAX_VALUE = unknown
    boolean takesParameter(char modeLetter, boolean adding);
}
```

**Fallbacks** when `005` is absent or omits a token:

- `PREFIX` → `(qaohv)~&@%+` — the same five prefixes the current hardcoded regex recognizes.
- `CHANMODES` → `beI,k,l,imnpst`.
- An unknown mode letter is treated as type D (no parameter). See *Risks*.

### `ChannelUserList`

Holds `channel → nick → set of mode letters`. All methods `synchronized`; `snapshot` returns a new
immutable list, so the EDT never reads a map being mutated by the socket reader thread.

Nicks are keyed by ASCII-lowercase canonical form; the server's display casing is retained for
rendering.

```java
final class ChannelUserList {
    ChannelUserList(ModeSpec spec);                          // spec read at query time

    void addNames(String channel, List<String> rawEntries);  // 353, accumulates into pending
    void endNames(String channel);                           // 366, commits pending over live
    void join(String channel, String nick);
    void part(String channel, String nick);
    List<String> quit(String nick);                          // returns affected channels
    void kick(String channel, String nick);
    List<String> rename(String oldNick, String newNick);     // returns affected channels
    void applyModeChange(String channel, List<String> modeParams);
    void removeChannel(String channel);
    void clear();

    List<Entry> snapshot(String channel);                    // sorted, immutable
    boolean hasChannel(String channel);

    @Value static class Entry { String nick; String prefix; int rank; }   // static: referenced
                                                                         // as ChannelUserList.Entry
}
```

**`addNames` / `endNames` pairing.** `353` arrives across multiple lines terminated by `366`.
`addNames` creates a pending buffer for the channel if absent and appends to it; `endNames` swaps
pending over live and clears it. This makes a `/names` refresh *replace* the roster rather than
merge into it, so users who left while we were desynced are dropped. `endNames` with no pending
buffer is a no-op.

**Multi-prefix.** A raw entry's leading characters are consumed while they are known prefix chars,
so `@+bob` yields modes `{o, v}` and `bob` yields `{}`.

**`applyModeChange`.** Walks the mode string left to right tracking the current `+`/`-` sign,
pulling one parameter off the queue for each letter where `spec.takesParameter(letter, adding)` is
true. Only prefix-mode letters mutate user state; the rest are consumed for alignment and
discarded. A mode targeting an unknown nick is ignored.

**Sorting.** `rank` ascending (`0` = `~` highest), then `String.CASE_INSENSITIVE_ORDER` on nick.
Users with no prefix mode sort last with `rank = Integer.MAX_VALUE` and `prefix = ""`.

### Protocol Layer — `SimpleIrcClient`

Replace the `channelUsers` field with:

```java
private final ModeSpec modeSpec = ModeSpec.defaults();
private final ChannelUserList channelUserList = new ChannelUserList(modeSpec);
```

Delete `USER_PREFIXES` (superseded by `ModeSpec`).

Add one event type to `IrcEvent.Type`:

```java
USERS_CHANGED    // target = channel; source, message, additionalData all null
```

**`handleNumeric` changes**

| Numeric | Change |
|---|---|
| `5` (ISUPPORT) | New case. `modeSpec.applyIsupport(params.subList(1, params.size()))`. The leading nick and the trailing human-readable description are not `KEY=VALUE` pairs and are skipped by the parser. |
| `353` | Pass the raw entries through to `channelUserList.addNames(channel, entries)` **without stripping prefixes**. Continue firing the existing `NAMES` event unchanged so `/names` still prints. |
| `366` | New case. `channelUserList.endNames(channel)` then fire `USERS_CHANGED`. |

**`processCommand` changes** — each replaces the ad-hoc `channelUsers` mutation with the
equivalent `channelUserList` call, then fires `USERS_CHANGED` for every affected channel:

| Command | Call | Notes |
|---|---|---|
| `JOIN` | `join(channel, nick)` | |
| `PART` | `part(channel, nick)`, or `removeChannel(channel)` when it is us | matches existing self/other branch |
| `QUIT` | `quit(nick)` → affected channels | the affected list must be captured **before** removal; the existing code already collects channels first |
| `NICK` | `rename(old, new)` → affected channels | |
| `KICK` | `kick(channel, nick)`, or `removeChannel(channel)` when it is us | |
| `MODE` (target starts with `#`) | `applyModeChange(target, params.subList(1, …))` | fires `USERS_CHANGED` **in addition to** the existing `CHANNEL_MODE` display event |

Also: `partChannel()` (~line 208) swaps its `channelUsers.remove(channel)` for
`channelUserList.removeChannel(channel)`, and disconnect calls `channelUserList.clear()`.

New accessor:

```java
public List<ChannelUserList.Entry> getChannelUsers(String channel);
```

### Adapter Layer — `IrcAdapter`

- Delete the dead `channelUsers` field at line 23, plus any imports it orphans.
- Handle the new event. `IrcAdapter` already holds a `panel` reference, so **no new callback needs
  to be threaded through `IrcPlugin`**:

```java
case USERS_CHANGED:
    if (panel != null) {
        String channel = event.getTarget();
        SwingUtilities.invokeLater(() ->
            panel.setChannelUsers(channel, client.getChannelUsers(channel)));
    }
    break;
```

`IrcPlugin` requires no changes.

### UI Layer — `IrcPanel`

**New state**

```java
private final Map<String, List<ChannelUserList.Entry>> channelUserSnapshots = new ConcurrentHashMap<>();
private final JComboBox<String> nickDropdown = getNickComboBox();
```

**Placement.** `row2` is a `JPanel(new FlowLayout(FlowLayout.RIGHT))`. FlowLayout lays components
out left-to-right within the right-aligned group, so adding `nickDropdown` *before* `bufferDropdown`
puts it to its left:

```java
row2.add(nickDropdown);
row2.add(bufferDropdown);
```

**Model.** Index `0` is a header, `Users (12)`, which is not a selectable action. Indices `1..n` are
entries rendered as `prefix + nick`, e.g. `@bob`.

**Renderer.** A `DefaultListCellRenderer` mirroring `getStringJComboBox()`. The header renders in
`Color.GRAY`; each entry renders in the same color the nick gets in chat. The renderer must strip
the prefix before hashing so `@bob` and `bob` resolve to the same color.

**`setChannelUsers(String channel, List<Entry> entries)`.** Stores the snapshot, and repopulates the
dropdown only when `channel.equals(getCurrentChannel())`.

**`repopulateNickDropdown()`.** Detaches action listeners, clears, adds the header, adds one item per
entry, `setSelectedIndex(0)`, `setEnabled(currentChannel.startsWith("#"))`, reattaches listeners.
This mirrors the existing detach/reattach pattern in `renameBufferDropdownItem` and is required
here: opening a PM from the dropdown triggers a tab change, which repopulates the dropdown while
still inside its own `ActionListener`.

**Action.** On selection with `index >= 1`: strip the leading prefix character, then

```java
addChannel(nick);
setFocusedChannel(nick);
```

then snap back to index `0`. The dropdown is an action menu, not a persistent selection.
`addChannel` already no-ops when the buffer exists, and `setFocusedChannel` already selects both the
tab and the buffer dropdown, so this covers both open-new and focus-existing.

**Repopulation triggers.** The existing `tabbedPane.addChangeListener` gains a
`repopulateNickDropdown()` call. Because `setFocusedChannel` drives `tabbedPane.setSelectedIndex`,
that single hook covers tab clicks, buffer-dropdown selection, `/page` cycling, and PM opening.

**Cleanup.** `removeChannel` also drops the channel from `channelUserSnapshots`.

### Nick Colors (folded in)

The chat pane and the dropdown renderer must agree on a nick's color, so the selection logic moves
into one shared helper. This also delivers the previously-approved palette extension.

Replacing `IrcPanel.java:608-609`:

```java
private static final String[] NICK_COLOR_IDS = {
    "02","03","04","05","06","07","08","09","10","11","12","13",
    "64","65","66","67","68","69","70","71","72","73","74","75",
    "76","77","78","79","80","81","82","83","84","85","86","87"
};

static String nickColorId(String nick) {
    return NICK_COLOR_IDS[Math.floorMod(nick.hashCode(), NICK_COLOR_IDS.length)];
}
```

36 colors total. Extended rows `64`–`87` are the vivid and pastel bands, all legible on the dark
panel; the near-black rows (`16`–`39`) and the dark grays (`88`–`93`) are excluded. `htmlColorById`
already resolves these via `extendedColorById`.

`Math.floorMod` replaces `Math.abs(...) % ...`, which returns a negative index when
`nick.hashCode() == Integer.MIN_VALUE` (`Math.abs` of that value is itself, still negative) and
would throw `ArrayIndexOutOfBoundsException`.

## Testing

TDD, RED before GREEN, one behavior per test.

| File | Covers |
|---|---|
| `ModeSpecTest` | `PREFIX`/`CHANMODES` parsing; fallbacks with no `005` and with a partial `005`; parameter rules for types A/B/C/D; prefix modes always taking a parameter; rank ordering; unparseable tokens ignored |
| `ChannelUserListTest` | `NAMES` single and multi-prefix; `addNames`/`endNames` replacing rather than merging; join/part/kick; quit and rename returning the right affected channels; `+o` / `-v`; compound `+mo bob`; mixed-sign `+o-v bob carol`; unknown mode letters; sort by rank then alpha; case-insensitive nick matching; snapshot immutability |
| `IrcNickColorTest` | Palette contains `02`–`13` and `64`–`87`, excludes `16` and `88`, size 36, every entry resolves to a real hex rather than the `"black"` fallback; `nickColorId` deterministic and always in range, including `Integer.MIN_VALUE`-hashing and empty nicks |
| `IrcPanelNickListTest` | Header snap-back after selection; prefix stripped before opening the PM; PM buffer created and focused; dropdown disabled on non-channel buffers; repopulation on tab change; no `ConcurrentModificationException` when repopulating from inside the action listener |

Build requires `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64`; the default Java 21 is JRE-only.

## Out of Scope (YAGNI)

- Right-click context menu for op/deop/kick/ban/whois.
- Channel-wide mode state in the UI (`+m`, `+t`, keys, limits) — these keep printing to the chat
  pane exactly as they do today.
- Away/idle marking, which needs `WHO` polling or the `away-notify` capability.
- A nicklist in the in-game `IrcOverlay`.
- User-configurable sort order or a nicklist toggle in `IrcConfig`.

## Risks and Stated Assumptions

**Unknown mode letters are assumed parameterless.** When a server uses a mode letter absent from
both `PREFIX` and `CHANMODES`, and that letter *does* take a parameter, the remaining parameters
misalign and a subsequent `+o` in the same command could op the wrong user. Assuming type D is the
conventional client choice because type D is by far the largest class. Recovery is the existing
`/names` command: `366` replaces the roster wholesale, so one refresh repairs any drift.

**ASCII case folding, not RFC1459.** RFC1459 treats `[ ] \ ^` as the lowercase of `{ } | ~`. Using
plain ASCII lowercase means a nick differing only in those characters could be treated as two
users. Rare in practice, and a contained change if it ever matters.

**Opening a PM with your own nick is permitted.** `IrcPanel` does not know the current nick — it
lives on `IrcAdapter` — and threading it through for this one guard is not worth the coupling.
Selecting yourself opens a self-PM buffer, which is harmless and is what `/query <self>` already
does today.
