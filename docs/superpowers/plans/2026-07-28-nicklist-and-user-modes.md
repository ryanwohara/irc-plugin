# Nicklist and User Mode Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a channel nicklist dropdown to the IRC side panel, backed by correct per-channel user mode tracking (owner/admin/op/halfop/voice), where selecting a nick opens a PM buffer.

**Architecture:** Two new pure classes — `ModeSpec` (parses the server's `PREFIX=`/`CHANMODES=` from numeric `005`) and `ChannelUserList` (holds `channel → nick → modes`, applies every membership event) — are owned by `SimpleIrcClient`. The client fires a new `USERS_CHANGED` event; `IrcAdapter` forwards the snapshot to `IrcPanel`, which renders it in a `JComboBox` placed left of the existing buffer dropdown.

**Tech Stack:** Java 11, Swing, Lombok (`@Value`, `@Getter`), JUnit 4.12, Gradle.

## Global Constraints

- **Build requires Java 11.** Every Gradle invocation must be prefixed with `export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64` — the default Java 21 on this machine is JRE-only and has no `javac`.
- **Do not run `git push`.** Commits stay local.
- **Do not add a `Co-Authored-By` trailer** to commit messages. Match the repo's terse conventional-commit style (`feat:`, `fix:`, `docs:`, `chore:`).
- Target bytecode is Java 11 (`options.release.set(11)` in `build.gradle`) — no `record`, no `var` in fields, no `switch` expressions.
- All new classes go in package `com.irc`, under `src/main/java/com/irc/`.
- Tests are JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`) — **not** JUnit 5.
- New protocol/state classes must be package-private and free of Swing and socket dependencies, matching the existing `InputHistory` pattern.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/com/irc/ModeSpec.java` | Create | Parse and answer questions about the server's mode vocabulary |
| `src/main/java/com/irc/ChannelUserList.java` | Create | Hold and mutate per-channel user→modes state; produce sorted snapshots |
| `src/main/java/com/irc/SimpleIrcClient.java` | Modify | Feed protocol events into `ChannelUserList`; fire `USERS_CHANGED` |
| `src/main/java/com/irc/IrcAdapter.java` | Modify | Delete dead map; forward `USERS_CHANGED` to the panel |
| `src/main/java/com/irc/IrcPanel.java` | Modify | Nick color helper; nicklist dropdown UI |
| `src/test/java/com/irc/ModeSpecTest.java` | Create | Task 1 tests |
| `src/test/java/com/irc/ChannelUserListTest.java` | Create | Task 2 tests |
| `src/test/java/com/irc/IrcNickColorTest.java` | Create | Task 3 tests |
| `src/test/java/com/irc/SimpleIrcClientUsersTest.java` | Create | Task 4 tests |
| `src/test/java/com/irc/IrcPanelNickListTest.java` | Create | Task 5 tests |

**Task order rationale:** Tasks 1–3 are independent leaves. Task 4 needs 1 and 2. Task 5 needs 2 and 3. Task 6 is the final bridge and needs 4 and 5, so it comes last — every earlier task compiles and tests green on its own.

---

### Task 1: `ModeSpec` — parse the server's mode vocabulary

**Files:**
- Create: `src/main/java/com/irc/ModeSpec.java`
- Test: `src/test/java/com/irc/ModeSpecTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `static ModeSpec defaults()`
  - `void applyIsupport(List<String> tokens)`
  - `boolean isPrefixMode(char modeLetter)`
  - `char prefixFor(char modeLetter)` — `'\0'` when not a prefix mode
  - `Character modeForPrefix(char prefixChar)` — `null` when not a prefix char
  - `int rankOf(char modeLetter)` — `0` highest, `Integer.MAX_VALUE` unknown
  - `boolean takesParameter(char modeLetter, boolean adding)`

**Background the implementer needs:** `RPL_ISUPPORT` (numeric `005`) advertises server capabilities as `KEY=VALUE` tokens. Two matter here:

- `PREFIX=(qaohv)~&@%+` — the letters in parentheses are channel-membership mode letters; the trailing characters are their display prefixes, positionally matched. Position also defines rank, index `0` being the most privileged.
- `CHANMODES=beI,k,l,imnpst` — four comma-separated groups defining whether a mode letter consumes a parameter: **A** (lists, e.g. bans) always; **B** (settings, e.g. key) always; **C** (set-only, e.g. limit) only when adding; **D** (flags) never. Membership modes from `PREFIX` are *not* listed here and always consume a parameter.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/irc/ModeSpecTest.java`:

```java
package com.irc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The server's mode vocabulary, learned from RPL_ISUPPORT (numeric 005).
 *
 * PREFIX=(qaohv)~&@%+ maps membership mode letters to display prefixes positionally, and the
 * position is also the rank (0 = most privileged). CHANMODES=A,B,C,D says which mode letters
 * consume a parameter: A and B always, C only when adding, D never. Membership modes are absent
 * from CHANMODES and always consume one. Knowing this is what keeps "MODE #c +mo bob" from
 * opping the wrong user.
 */
public class ModeSpecTest {

    @Test
    public void defaultsRecognizeTheClassicFivePrefixes() {
        ModeSpec spec = ModeSpec.defaults();
        assertEquals('~', spec.prefixFor('q'));
        assertEquals('&', spec.prefixFor('a'));
        assertEquals('@', spec.prefixFor('o'));
        assertEquals('%', spec.prefixFor('h'));
        assertEquals('+', spec.prefixFor('v'));
    }

    @Test
    public void defaultsRankOwnerAboveVoice() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.rankOf('q') < spec.rankOf('o'));
        assertTrue(spec.rankOf('o') < spec.rankOf('v'));
    }

    @Test
    public void unknownModeLetterHasLowestRank() {
        assertEquals(Integer.MAX_VALUE, ModeSpec.defaults().rankOf('Z'));
    }

    @Test
    public void prefixCharMapsBackToItsModeLetter() {
        ModeSpec spec = ModeSpec.defaults();
        assertEquals(Character.valueOf('o'), spec.modeForPrefix('@'));
        assertNull(spec.modeForPrefix('x'));
    }

    @Test
    public void isupportReplacesThePrefixTable() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("PREFIX=(ov)@+"));
        assertEquals(0, spec.rankOf('o'));
        assertFalse("halfop is gone once the server says it has none", spec.isPrefixMode('h'));
    }

    @Test
    public void isupportSkipsTokensThatAreNotKeyValuePairs() {
        ModeSpec spec = ModeSpec.defaults();
        // A real 005 line: leading nick, then tokens, then a trailing description.
        spec.applyIsupport(Arrays.asList("mynick", "PREFIX=(ov)@+", "are supported by this server"));
        assertEquals('@', spec.prefixFor('o'));
        assertFalse(spec.isPrefixMode('h'));
    }

    @Test
    public void unparseablePrefixValueLeavesThePreviousTableIntact() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("PREFIX=garbage"));
        assertEquals('@', spec.prefixFor('o'));
    }

    @Test
    public void prefixModesAlwaysTakeAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.takesParameter('o', true));
        assertTrue(spec.takesParameter('o', false));
        assertTrue(spec.takesParameter('v', false));
    }

    @Test
    public void typeAAndTypeBModesAlwaysTakeAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue("b is a list mode", spec.takesParameter('b', true));
        assertTrue(spec.takesParameter('b', false));
        assertTrue("k is a settings mode", spec.takesParameter('k', true));
        assertTrue(spec.takesParameter('k', false));
    }

    @Test
    public void typeCModeTakesAParameterOnlyWhenAdding() {
        ModeSpec spec = ModeSpec.defaults();
        assertTrue(spec.takesParameter('l', true));
        assertFalse(spec.takesParameter('l', false));
    }

    @Test
    public void typeDModeNeverTakesAParameter() {
        ModeSpec spec = ModeSpec.defaults();
        assertFalse(spec.takesParameter('m', true));
        assertFalse(spec.takesParameter('m', false));
    }

    @Test
    public void unknownModeLetterIsAssumedParameterless() {
        // Stated assumption in the spec: type D is the largest class, so it is the safer guess.
        ModeSpec spec = ModeSpec.defaults();
        assertFalse(spec.takesParameter('Z', true));
        assertFalse(spec.takesParameter('Z', false));
    }

    @Test
    public void isupportReplacesTheChanmodesTable() {
        ModeSpec spec = ModeSpec.defaults();
        spec.applyIsupport(Collections.singletonList("CHANMODES=b,k,l,imnpstZ"));
        assertFalse("Z is now a known flag mode", spec.takesParameter('Z', true));
        assertTrue(spec.takesParameter('b', true));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.ModeSpecTest'
```

Expected: compilation failure — `cannot find symbol: class ModeSpec`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/irc/ModeSpec.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.ModeSpecTest'
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/irc/ModeSpec.java src/test/java/com/irc/ModeSpecTest.java
git commit -m "feat: Parse ISUPPORT PREFIX and CHANMODES"
```

---

### Task 2: `ChannelUserList` — per-channel user and mode state

**Files:**
- Create: `src/main/java/com/irc/ChannelUserList.java`
- Test: `src/test/java/com/irc/ChannelUserListTest.java`

**Interfaces:**
- Consumes: `ModeSpec` from Task 1 — specifically `modeForPrefix`, `isPrefixMode`, `takesParameter`, `rankOf`, `prefixFor`.
- Produces:
  - `ChannelUserList(ModeSpec spec)`
  - `void addNames(String channel, List<String> rawEntries)`
  - `void endNames(String channel)`
  - `void join(String channel, String nick)`
  - `void part(String channel, String nick)`
  - `void kick(String channel, String nick)`
  - `List<String> quit(String nick)` — affected channel names
  - `List<String> rename(String oldNick, String newNick)` — affected channel names
  - `void applyModeChange(String channel, List<String> modeParams)`
  - `void removeChannel(String channel)`
  - `void clear()`
  - `List<Entry> snapshot(String channel)`
  - `static class Entry` with `getNick()`, `getPrefix()`, `getRank()`

**Design notes the implementer needs:**

- **Channel and nick keys are ASCII-lowercased**, but the server's display casing is preserved for rendering. `quit`/`rename` return *display* channel names, because callers use them to match `IrcPanel.getCurrentChannel()`.
- **`addNames` accumulates, `endNames` commits.** Numeric `353` arrives across several lines and is terminated by `366`. Buffering into `pending` and swapping on `endNames` makes a `/names` refresh *replace* the roster, so users who left during a desync get dropped. `endNames` with no pending buffer is a no-op.
- **Multi-prefix:** consume leading characters while they are known prefixes, so `@+bob` yields modes `{o, v}`.
- **`applyModeChange` must consume a parameter for every letter where `takesParameter` says so**, even for non-membership modes it then discards — that consumption is the entire point, it keeps the remaining parameters aligned.
- **All public methods are `synchronized`** and `snapshot` returns an unmodifiable copy: this is written from the socket reader thread and read from the Swing EDT.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/irc/ChannelUserListTest.java`:

```java
package com.irc;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Per-channel roster of who is present and what membership modes they hold.
 *
 * Fed by NAMES (353/366), JOIN, PART, QUIT, KICK, NICK and MODE. The interesting cases are
 * parameter alignment in compound MODE strings, NAMES replacing rather than merging, and
 * QUIT/NICK reporting which channels they touched so only those get redrawn.
 */
public class ChannelUserListTest {

    private ChannelUserList users;

    @Before
    public void setUp() {
        users = new ChannelUserList(ModeSpec.defaults());
    }

    /** Loads a roster the way the protocol does: one or more 353 lines, then a 366. */
    private void names(String channel, String... rawEntries) {
        users.addNames(channel, Arrays.asList(rawEntries));
        users.endNames(channel);
    }

    private List<String> nicks(String channel) {
        List<String> result = new java.util.ArrayList<>();
        for (ChannelUserList.Entry entry : users.snapshot(channel)) {
            result.add(entry.getPrefix() + entry.getNick());
        }
        return result;
    }

    @Test
    public void namesPopulatesUsersAndTheirPrefixes() {
        names("#chan", "@bob", "+dave", "erin");
        assertEquals(Arrays.asList("@bob", "+dave", "erin"), nicks("#chan"));
    }

    @Test
    public void namesHandlesMultiplePrefixesAndShowsTheHighest() {
        names("#chan", "@+bob");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void multiplePrefixesAreAllRetainedSoRevokingOneLeavesTheOther() {
        names("#chan", "@+bob");
        users.applyModeChange("#chan", Arrays.asList("-o", "bob"));
        assertEquals("voice survives losing op", Collections.singletonList("+bob"), nicks("#chan"));
    }

    @Test
    public void namesAcrossMultipleLinesAccumulateBeforeTheCommit() {
        users.addNames("#chan", Arrays.asList("@bob", "+dave"));
        users.addNames("#chan", Collections.singletonList("erin"));
        users.endNames("#chan");
        assertEquals(Arrays.asList("@bob", "+dave", "erin"), nicks("#chan"));
    }

    @Test
    public void aSecondNamesRoundReplacesRatherThanMerges() {
        names("#chan", "@bob", "+dave", "erin");
        names("#chan", "@bob");
        assertEquals("stale users are dropped", Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void endNamesWithoutPendingIsANoOp() {
        names("#chan", "@bob");
        users.endNames("#chan");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void joinAddsAnUnprivilegedUser() {
        names("#chan", "@bob");
        users.join("#chan", "erin");
        assertEquals(Arrays.asList("@bob", "erin"), nicks("#chan"));
    }

    @Test
    public void partRemovesTheUser() {
        names("#chan", "@bob", "erin");
        users.part("#chan", "erin");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void kickRemovesTheUser() {
        names("#chan", "@bob", "erin");
        users.kick("#chan", "erin");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void quitRemovesTheUserFromEveryChannelAndReportsWhich() {
        names("#one", "erin", "bob");
        names("#two", "erin");
        names("#three", "bob");

        List<String> affected = users.quit("erin");

        assertEquals(2, affected.size());
        assertTrue(affected.contains("#one"));
        assertTrue(affected.contains("#two"));
        assertEquals(Collections.singletonList("bob"), nicks("#one"));
        assertEquals(Collections.emptyList(), nicks("#two"));
    }

    @Test
    public void renameKeepsModesAndReportsAffectedChannels() {
        names("#one", "@bob");
        names("#two", "+bob");

        List<String> affected = users.rename("bob", "robert");

        assertEquals(2, affected.size());
        assertEquals(Collections.singletonList("@robert"), nicks("#one"));
        assertEquals(Collections.singletonList("+robert"), nicks("#two"));
    }

    @Test
    public void modeGrantsOp() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+o", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void modeRevokesVoice() {
        names("#chan", "+bob");
        users.applyModeChange("#chan", Arrays.asList("-v", "bob"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void compoundModeSkipsTheParameterlessLetter() {
        // The regression this whole design exists to prevent: "m" takes no parameter, so "bob"
        // belongs to "o". Getting this wrong ops nobody, or the wrong person.
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+mo", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void mixedSignModeAppliesEachLetterToItsOwnParameter() {
        names("#chan", "bob", "+carol");
        users.applyModeChange("#chan", Arrays.asList("+o-v", "bob", "carol"));
        assertEquals(Arrays.asList("@bob", "carol"), nicks("#chan"));
    }

    @Test
    public void removingATypeCModeConsumesNoParameter() {
        // "l" (limit) takes a parameter when set but not when removed, so "bob" belongs to "o".
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("-lo", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void settingATypeBModeConsumesItsParameter() {
        // "k" (key) always takes a parameter, so "secret" is the key and "bob" belongs to "o".
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+ko", "secret", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void modeTargetingAnUnknownNickIsIgnored() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+o", "nosuchuser"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void modeWithMissingParametersDoesNotThrow() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Collections.singletonList("+o"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void modeOnAnUnknownChannelIsIgnored() {
        users.applyModeChange("#nosuchchannel", Arrays.asList("+o", "bob"));
        assertEquals(Collections.emptyList(), nicks("#nosuchchannel"));
    }

    @Test
    public void snapshotSortsByRankThenCaseInsensitiveAlphabetically() {
        names("#chan", "zoe", "@Bob", "+dave", "~founder", "Alice", "%carol");
        assertEquals(
                Arrays.asList("~founder", "@Bob", "%carol", "+dave", "Alice", "zoe"),
                nicks("#chan"));
    }

    @Test
    public void nickMatchingIsCaseInsensitiveButDisplayCasingIsKept() {
        names("#chan", "@Bob");
        users.applyModeChange("#chan", Arrays.asList("-o", "bob"));
        assertEquals(Collections.singletonList("Bob"), nicks("#chan"));
    }

    @Test
    public void snapshotIsImmutable() {
        names("#chan", "bob");
        List<ChannelUserList.Entry> snapshot = users.snapshot("#chan");
        try {
            snapshot.add(new ChannelUserList.Entry("evil", "", 0));
            org.junit.Assert.fail("snapshot must not be modifiable");
        } catch (UnsupportedOperationException expected) {
            // the EDT reads this while the socket thread mutates the source
        }
    }

    @Test
    public void snapshotOfAnUnknownChannelIsEmpty() {
        assertEquals(Collections.emptyList(), users.snapshot("#nope"));
    }

    @Test
    public void removeChannelDropsItsRoster() {
        names("#chan", "bob");
        users.removeChannel("#chan");
        assertEquals(Collections.emptyList(), users.snapshot("#chan"));
    }

    @Test
    public void clearDropsEverything() {
        names("#one", "bob");
        names("#two", "erin");
        users.clear();
        assertEquals(Collections.emptyList(), users.snapshot("#one"));
        assertEquals(Collections.emptyList(), users.snapshot("#two"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.ChannelUserListTest'
```

Expected: compilation failure — `cannot find symbol: class ChannelUserList`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/irc/ChannelUserList.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.ChannelUserListTest'
```

Expected: PASS, 25 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/irc/ChannelUserList.java src/test/java/com/irc/ChannelUserListTest.java
git commit -m "feat: Track per-channel users and membership modes"
```

---

### Task 3: Shared nick color helper

**Files:**
- Modify: `src/main/java/com/irc/IrcPanel.java:607-612` (the `config.colorizedNicks()` block inside `ChannelPane.formatPanelMessage`)
- Test: `src/test/java/com/irc/IrcNickColorTest.java`

**Interfaces:**
- Consumes: existing `IrcPanel.ChannelPane.htmlColorById(String)`.
- Produces: `static String IrcPanel.ChannelPane.nickColorId(String nick)` — returns a palette *code* (e.g. `"64"`), which callers pass to `htmlColorById` to get a hex string.

**Why this is its own task:** Task 5's dropdown renderer must color a nick identically to the chat pane. That requires one shared function, so the selection logic moves out of the render path. It also delivers the previously-approved palette extension and fixes a latent crash.

**The crash being fixed:** `Math.abs(Integer.MIN_VALUE)` returns `Integer.MIN_VALUE` — still negative — so the current `Math.abs(sender.hashCode()) % length` yields a negative index and throws `ArrayIndexOutOfBoundsException`. `"polygenelubricants".hashCode()` is exactly `Integer.MIN_VALUE`, which the test uses as its fixture. `Math.floorMod` is the fix.

**Note on the classic codes:** `02` (`#000080` navy), `05` (`#800000` maroon) and `06` (`#800080` purple) are dark on the dark panel. They are kept because the approved design was to *add* rows 64–87 to the existing 2–13, not to re-curate the classic range. Leave them.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/irc/IrcNickColorTest.java`:

```java
package com.irc;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The palette nicks are colored from, shared by the chat pane and the nicklist dropdown so a
 * nick looks the same in both.
 *
 * 36 codes: the classic 02-13 plus the extended palette's vivid and pastel rows 64-87. The
 * near-black rows (16-39) and dark grays (88-93) are excluded as unreadable on the dark panel.
 */
public class IrcNickColorTest {

    /** Reflection-free access: the palette is exercised through nickColorId's full range. */
    private static Set<String> allProducedIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            ids.add(IrcPanel.ChannelPane.nickColorId("nick" + i));
        }
        return ids;
    }

    @Test
    public void paletteHasThirtySixColors() {
        assertEquals(36, allProducedIds().size());
    }

    @Test
    public void paletteKeepsTheClassicCodesTwoThroughThirteen() {
        Set<String> ids = allProducedIds();
        for (String classic : Arrays.asList("02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13")) {
            assertTrue("classic code " + classic + " should still be in the palette", ids.contains(classic));
        }
    }

    @Test
    public void paletteIncludesExtendedRowsSixtyFourThroughEightySeven() {
        Set<String> ids = allProducedIds();
        for (int code = 64; code <= 87; code++) {
            assertTrue("extended code " + code + " should be in the palette", ids.contains(String.valueOf(code)));
        }
    }

    @Test
    public void paletteExcludesUnreadableRows() {
        Set<String> ids = allProducedIds();
        assertFalse("00/01 are pure white and black", ids.contains("00") || ids.contains("01"));
        assertFalse("16 is near-black", ids.contains("16"));
        assertFalse("88 is a dark gray", ids.contains("88"));
    }

    @Test
    public void everyPaletteEntryResolvesToARealColor() {
        for (String id : allProducedIds()) {
            String color = IrcPanel.ChannelPane.htmlColorById(id);
            assertTrue("code " + id + " must resolve to a hex color, got: " + color, color.startsWith("#"));
        }
    }

    @Test
    public void nickColorIdIsDeterministic() {
        assertEquals(IrcPanel.ChannelPane.nickColorId("bob"), IrcPanel.ChannelPane.nickColorId("bob"));
    }

    @Test
    public void nickColorIdHandlesTheMinimumHashCodeWithoutCrashing() {
        // Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE, so the old
        // "Math.abs(h) % length" produced a negative index here. Math.floorMod does not.
        assertEquals(Integer.MIN_VALUE, "polygenelubricants".hashCode());
        String id = IrcPanel.ChannelPane.nickColorId("polygenelubricants");
        assertTrue("must return a usable palette code, got: " + id, allProducedIds().contains(id));
    }

    @Test
    public void nickColorIdHandlesAnEmptyNick() {
        assertTrue(allProducedIds().contains(IrcPanel.ChannelPane.nickColorId("")));
    }

    @Test
    public void nickColorIdSpreadsAcrossTheWholePalette() {
        List<String> sampled = Arrays.asList(
                IrcPanel.ChannelPane.nickColorId("alice"),
                IrcPanel.ChannelPane.nickColorId("bob"),
                IrcPanel.ChannelPane.nickColorId("carol"));
        assertEquals("distinct nicks should not all collide", 3, new HashSet<>(sampled).size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.IrcNickColorTest'
```

Expected: compilation failure — `cannot find symbol: method nickColorId(String)`.

- [ ] **Step 3: Write the implementation**

In `src/main/java/com/irc/IrcPanel.java`, inside the `ChannelPane` static nested class, add the palette and helper next to `htmlColorById` (around line 639):

```java
        /**
         * Palette nicks are colored from: the classic 02-13 plus the extended palette's vivid and
         * pastel rows 64-87. Near-black rows (16-39) and dark grays (88-93) are excluded as
         * unreadable on the dark panel. Shared with the nicklist dropdown so a nick looks the
         * same in both places.
         */
        private static final String[] NICK_COLOR_IDS = {
                "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13",
                "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75",
                "76", "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87"
        };

        /**
         * Deterministic palette code for a nick. Uses floorMod rather than abs: for a nick whose
         * hashCode is Integer.MIN_VALUE, Math.abs returns Integer.MIN_VALUE again and the index
         * goes negative.
         */
        static String nickColorId(String nick) {
            return NICK_COLOR_IDS[Math.floorMod(nick.hashCode(), NICK_COLOR_IDS.length)];
        }
```

Then replace the block at lines 607-612:

```java
            if (config.colorizedNicks()) {
                String[] viableColorIds = new String[]{"02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13"};
                String colorId = viableColorIds[Math.abs(sender.hashCode()) % viableColorIds.length];
                String senderColor = htmlColorById(colorId);
                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }
```

with:

```java
            if (config.colorizedNicks()) {
                String senderColor = htmlColorById(nickColorId(message.getSender()));
                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }
```

Note the hash input changes from the HTML-escaped `sender` to the raw `message.getSender()`. This keeps the chat pane and the dropdown (which only ever has raw nicks) in agreement. For legal IRC nicks `escapeHtml4` is the identity function, so no existing nick changes color.

- [ ] **Step 4: Run the test to verify it passes**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.IrcNickColorTest'
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Run the full suite to confirm no regression**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test
```

Expected: PASS, all pre-existing tests still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/irc/IrcPanel.java src/test/java/com/irc/IrcNickColorTest.java
git commit -m "feat: Extend nick colors to the >15 palette"
```

---

### Task 4: Wire the protocol layer

**Files:**
- Modify: `src/main/java/com/irc/SimpleIrcClient.java`
- Test: `src/test/java/com/irc/SimpleIrcClientUsersTest.java`

**Interfaces:**
- Consumes: `ModeSpec` (Task 1), `ChannelUserList` and `ChannelUserList.Entry` (Task 2).
- Produces:
  - `SimpleIrcClient.IrcEvent.Type.USERS_CHANGED` — `target` is the channel; `source`, `message`, `additionalData` are all `null`.
  - `List<ChannelUserList.Entry> getChannelUsers(String channel)` — package-private.

**How to test this:** `SimpleIrcClientTest` already defines a `TestableIrcClient` nested subclass that overrides `sendRawLine` to capture output, and `processLine` is package-private. The new test defines its own equivalent subclass and registers a listener via the public `addEventListener` to capture fired events — no socket needed.

**Field changes:** delete `private static final Pattern USER_PREFIXES` (line 32) and `private final Map<String, Set<String>> channelUsers` (line 50); add:

```java
    private final ModeSpec modeSpec = ModeSpec.defaults();
    private final ChannelUserList channelUserList = new ChannelUserList(modeSpec);
```

`HashMap` may become an unused import — let the compiler tell you.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/irc/SimpleIrcClientUsersTest.java`:

```java
package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The protocol layer feeding ChannelUserList and announcing changes.
 *
 * Every membership event must both update the roster and fire USERS_CHANGED for each channel it
 * touched, so the panel redraws exactly the affected buffers. Display events (NAMES, CHANNEL_MODE)
 * must keep firing unchanged so /names and mode messages still print to chat.
 */
public class SimpleIrcClientUsersTest {

    /** Feeds raw lines without a socket and records what came back out. */
    private static class RecordingClient extends SimpleIrcClient {
        final List<String> sentLines = new ArrayList<>();
        final List<IrcEvent> events = new ArrayList<>();

        RecordingClient() {
            addEventListener(events::add);
        }

        @Override
        public synchronized void sendRawLine(String line) {
            sentLines.add(line);
        }

        List<String> usersChangedChannels() {
            List<String> channels = new ArrayList<>();
            for (IrcEvent event : events) {
                if (event.getType() == IrcEvent.Type.USERS_CHANGED) {
                    channels.add(event.getTarget());
                }
            }
            return channels;
        }

        List<String> nicksIn(String channel) {
            List<String> nicks = new ArrayList<>();
            for (ChannelUserList.Entry entry : getChannelUsers(channel)) {
                nicks.add(entry.getPrefix() + entry.getNick());
            }
            return nicks;
        }

        boolean firedType(IrcEvent.Type type) {
            return events.stream().anyMatch(e -> e.getType() == type);
        }
    }

    /** A 353 followed by its 366 terminator, as the server sends them. */
    private static void loadNames(RecordingClient client, String channel, String names) {
        client.processLine(":server 353 me = " + channel + " :" + names);
        client.processLine(":server 366 me " + channel + " :End of /NAMES list");
    }

    @Test
    public void namesReplyPopulatesTheRosterWithPrefixesIntact() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob +dave erin");
        assertEquals(java.util.Arrays.asList("@bob", "+dave", "erin"), client.nicksIn("#chan"));
    }

    @Test
    public void namesReplyStillFiresTheDisplayEventSoSlashNamesKeepsPrinting() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 353 me = #chan :@bob +dave");
        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.NAMES));
    }

    @Test
    public void endOfNamesAnnouncesTheChannel() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void isupportRetunesThePrefixTable() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 005 me PREFIX=(ov)@+ CHANMODES=b,k,l,imnpst :are supported");
        loadNames(client, "#chan", "@bob %carol");
        // With PREFIX=(ov)@+ the server has no halfop, so "%carol" is a nick starting with '%'.
        assertEquals(java.util.Arrays.asList("@bob", "%carol"), client.nicksIn("#chan"));
    }

    @Test
    public void joinAddsTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        client.events.clear();

        client.processLine(":erin!user@host JOIN #chan");

        assertEquals(java.util.Arrays.asList("@bob", "erin"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void partRemovesTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob erin");
        client.events.clear();

        client.processLine(":erin!user@host PART #chan :bye");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void quitAnnouncesEveryChannelTheUserWasIn() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#one", "erin bob");
        loadNames(client, "#two", "erin");
        loadNames(client, "#three", "bob");
        client.events.clear();

        client.processLine(":erin!user@host QUIT :gone");

        List<String> announced = client.usersChangedChannels();
        assertEquals(2, announced.size());
        assertTrue(announced.contains("#one"));
        assertTrue(announced.contains("#two"));
        assertFalse("#three had no erin", announced.contains("#three"));
    }

    @Test
    public void quitStillCarriesTheAffectedChannelsOnTheQuitEvent() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#one", "erin");
        client.events.clear();

        client.processLine(":erin!user@host QUIT :gone");

        SimpleIrcClient.IrcEvent quit = client.events.stream()
                .filter(e -> e.getType() == SimpleIrcClient.IrcEvent.Type.QUIT)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("#one", quit.getAdditionalData());
    }

    @Test
    public void nickChangeRenamesInPlaceAndKeepsModes() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        client.events.clear();

        client.processLine(":bob!user@host NICK robert");

        assertEquals(java.util.Collections.singletonList("@robert"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void kickRemovesTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob erin");
        client.events.clear();

        client.processLine(":bob!user@host KICK #chan erin :out");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertTrue(client.usersChangedChannels().contains("#chan"));
    }

    @Test
    public void channelModeIsAppliedToTheRoster() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +o bob");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertTrue(client.usersChangedChannels().contains("#chan"));
    }

    @Test
    public void compoundChannelModeAlignsItsParameters() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +mo bob");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
    }

    @Test
    public void channelModeStillFiresTheDisplayEvent() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +o bob");

        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_MODE));
    }

    @Test
    public void userModeOnANickIsNotTreatedAsAChannel() {
        RecordingClient client = new RecordingClient();
        client.processLine(":me!user@host MODE me +i");
        assertTrue("no channel changed", client.usersChangedChannels().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.SimpleIrcClientUsersTest'
```

Expected: compilation failure — `USERS_CHANGED` and `getChannelUsers` do not exist.

- [ ] **Step 3: Add the event type and the accessor**

In `SimpleIrcClient.IrcEvent.Type` (around line 726), add `USERS_CHANGED` to the enum:

```java
        public enum Type {
            CONNECT, DISCONNECT, REGISTERED, MESSAGE, ACTION, JOIN, PART, QUIT,
            NICK_CHANGE, KICK, NOTICE, SERVER_NOTICE, CHANNEL_MODE, USER_MODE,
            TOPIC, NAMES, NICK_IN_USE, ERROR, TOPIC_INFO, BAD_CHANNEL_KEY, WHOIS_REPLY,
            HISTORY_BATCH, SASL_SUCCESS, SASL_FAILED, USERS_CHANGED
        }
```

Add near the other accessors:

```java
    /** Current roster for a channel, sorted by rank then nick. Empty when unknown. */
    List<ChannelUserList.Entry> getChannelUsers(String channel) {
        return channelUserList.snapshot(channel);
    }

    /** Fires USERS_CHANGED for a channel whose roster just changed. */
    private void fireUsersChanged(String channel) {
        fireEvent(new IrcEvent(IrcEvent.Type.USERS_CHANGED, null, channel, null, null));
    }
```

- [ ] **Step 4: Replace the field and the command handlers**

Swap the fields as described in the task header, then rewrite these `processCommand` cases.

`JOIN` — add the roster call and the announcement:

```java
            case "JOIN":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    fireEvent(new IrcEvent(IrcEvent.Type.JOIN, sourceNick, channel, null, null));
                    channelUserList.join(channel, sourceNick);
                    fireUsersChanged(channel);
                    if (sourceNick.equals(nick) && capHistorySupported) {
                        sendRawLine("CHATHISTORY LATEST " + channel + " * 100");
                    }
                }
                break;
```

`PART`:

```java
            case "PART":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    String reason = params.size() > 1 ? params.get(1) : "";

                    if (!sourceNick.equals(nick)) {
                        fireEvent(new IrcEvent(IrcEvent.Type.PART, sourceNick, channel, reason, null));
                        channelUserList.part(channel, sourceNick);
                        fireUsersChanged(channel);
                    } else {
                        channelUserList.removeChannel(channel);
                    }
                }
                break;
```

`QUIT` — the roster now reports the affected channels, replacing the manual scan:

```java
            case "QUIT":
                String quitMessage = params.isEmpty() ? "" : params.get(0);

                List<String> userChannels = channelUserList.quit(sourceNick);
                fireEvent(new IrcEvent(IrcEvent.Type.QUIT, sourceNick, null, quitMessage, String.join(",", userChannels)));
                for (String quitChannel : userChannels) {
                    fireUsersChanged(quitChannel);
                }
                break;
```

`NICK`:

```java
            case "NICK":
                if (!params.isEmpty()) {
                    String newNick = params.get(0);
                    if (sourceNick.equals(this.nick)) {
                        this.nick = newNick;
                    }

                    userChannels = channelUserList.rename(sourceNick, newNick);
                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_CHANGE, sourceNick, null, newNick, String.join(",", userChannels)));
                    for (String renamedChannel : userChannels) {
                        fireUsersChanged(renamedChannel);
                    }
                }
                break;
```

`KICK`:

```java
            case "KICK":
                if (params.size() >= 2) {
                    String channel = params.get(0);
                    String kickedUser = params.get(1);
                    String kickMessage = params.size() > 2 ? params.get(2) : "";

                    fireEvent(new IrcEvent(IrcEvent.Type.KICK, sourceNick, channel, kickedUser + " " + kickMessage, null));
                    if (!kickedUser.equals(nick)) {
                        channelUserList.kick(channel, kickedUser);
                        fireUsersChanged(channel);
                    } else {
                        channelUserList.removeChannel(channel);
                    }
                }
                break;
```

`MODE` — apply to the roster in the channel branch, keeping the display event:

```java
            case "MODE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    StringBuilder modeString = new StringBuilder();
                    for (int i = 1; i < params.size(); i++) {
                        modeString.append(" ").append(params.get(i));
                    }

                    if (target.startsWith("#")) {
                        fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* " + sourceNick + " sets mode(s)", target, modeString.toString().trim(), null));
                        channelUserList.applyModeChange(target, params.subList(1, params.size()));
                        fireUsersChanged(target);
                    } else {
                        fireEvent(new IrcEvent(IrcEvent.Type.USER_MODE, sourceNick, target, modeString.toString().trim(), null));
                    }
                }
                break;
```

- [ ] **Step 5: Update the numeric handlers**

In `handleNumeric`, add a `005` case (the numeric arrives as the int `5`):

```java
            case 5: // RPL_ISUPPORT
                if (params.size() >= 2) {
                    modeSpec.applyIsupport(params.subList(1, params.size()));
                }
                break;
```

Replace the `353` case so prefixes survive:

```java
            case 353:
                if (params.size() >= 4) {
                    String channel = params.get(2);
                    String[] users = params.get(3).split(" ");
                    channelUserList.addNames(channel, java.util.Arrays.asList(users));
                    fireEvent(new IrcEvent(IrcEvent.Type.NAMES, null, channel, String.join(" ", users), null));
                }
                break;
```

Add the `366` case:

```java
            case 366: // RPL_ENDOFNAMES
                if (params.size() >= 2) {
                    String channel = params.get(1);
                    channelUserList.endNames(channel);
                    fireUsersChanged(channel);
                }
                break;
```

- [ ] **Step 6: Update the remaining `channelUsers` references**

In `leaveChannel(String, String)` (~line 208), replace `channelUsers.remove(channel);` with:

```java
            channelUserList.removeChannel(channel);
```

At the disconnect site (~line 179, where `connected = false;` precedes the `DISCONNECT` event), add:

```java
            channelUserList.clear();
```

Then confirm nothing else references the old field:

```bash
grep -n 'channelUsers\|USER_PREFIXES' src/main/java/com/irc/SimpleIrcClient.java
```

Expected: no output.

- [ ] **Step 7: Run the test to verify it passes**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.SimpleIrcClientUsersTest'
```

Expected: PASS, 14 tests.

- [ ] **Step 8: Run the full suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test
```

Expected: PASS. `SimpleIrcClientTest` in particular must stay green — it covers CAP negotiation and tag parsing on the same lines.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/irc/SimpleIrcClient.java src/test/java/com/irc/SimpleIrcClientUsersTest.java
git commit -m "feat: Feed membership events into the channel user list"
```

---

### Task 5: The nicklist dropdown

**Files:**
- Modify: `src/main/java/com/irc/IrcPanel.java`
- Test: `src/test/java/com/irc/IrcPanelNickListTest.java`

**Interfaces:**
- Consumes: `ChannelUserList.Entry` (Task 2), `ChannelPane.nickColorId` and `ChannelPane.htmlColorById` (Task 3), and the existing `addChannel(String)`, `setFocusedChannel(String)`, `getCurrentChannel()`.
- Produces: `public void setChannelUsers(String channel, List<ChannelUserList.Entry> entries)` — the method Task 6 calls.

**Design notes the implementer needs:**

- **Index into the entry list, do not parse the label.** The selected index maps to `displayedEntries.get(index - 1)` (index `0` is the header), so the prefix never has to be stripped from a string and the panel stays free of prefix knowledge.
- **Detach listeners while repopulating.** Selecting a nick focuses the new PM buffer, which fires the tab-change listener, which repopulates the dropdown — all while still inside the dropdown's own `ActionListener`. The existing `renameBufferDropdownItem` uses the same detach/reattach pattern for the same reason.
- **`repopulateNickDropdown` must tolerate a null `tabbedPane`**, since `setChannelUsers` can arrive before `initializeGui` runs.
- Add `import java.util.concurrent.ConcurrentHashMap;` — `IrcPanel` already imports `java.util.*` and `java.util.List`.

**How to build a testable `IrcPanel` (verified against the real build):**

`IrcPanel` has a no-arg constructor, but `addChannel` dereferences `config`, so a bare
`new IrcPanel()` throws `NullPointerException` the moment a nick is selected. `IrcConfig` is an
interface whose methods are all `default` except **two** — `username()` and `password()` — so an
anonymous stub overriding just those compiles and supplies working defaults for everything else.

Do **not** build the panel by injecting tabs into `tabbedPane` directly (the way
`IrcPanelRenameTest` does). Navigation maps a channel to a tab strictly by *position*:
`setFocusedChannel` computes an index by iterating `channelPanes`, then calls
`tabbedPane.setSelectedIndex(index)`. Adding tabs without matching `channelPanes` entries breaks
that alignment and `getCurrentChannel()` returns the wrong buffer. Drive `addChannel` instead so
both structures stay in step.

One gotcha: `config.channel()` defaults to `#rshelp`, and `addChannel` auto-focuses a channel
matching it. Avoid `#rshelp` as a fixture name, and focus the starting channel explicitly.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/irc/IrcPanelNickListTest.java`:

```java
package com.irc;

import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The nicklist dropdown: a compact action menu left of the buffer dropdown.
 *
 * Index 0 is a "Users (N)" header rather than a selectable user, and picking a real entry opens
 * that user's PM buffer then snaps back to the header - it is a menu, not a persistent selection.
 * Repopulation happens from inside the dropdown's own action listener (selecting a nick changes
 * the focused tab, which repopulates), so the listener detach/reattach is load-bearing.
 */
public class IrcPanelNickListTest {

    /**
     * Builds a panel through addChannel so channelPanes stays index-aligned with tabbedPane -
     * setFocusedChannel maps a channel to a tab by position, so injecting tabs directly would
     * desync the two and getCurrentChannel() would report the wrong buffer.
     */
    private static IrcPanel panelWith(String... channels) throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", stubConfig());
        set(panel, "tabbedPane", new JTabbedPane());

        for (String channel : channels) {
            panel.addChannel(channel);
        }
        panel.setFocusedChannel(channels[0]);
        return panel;
    }

    /** Every IrcConfig method is default except username() and password(). */
    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override
            public String username() {
                return "tester";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> nickDropdown(IrcPanel panel) throws Exception {
        Field f = IrcPanel.class.getDeclaredField("nickDropdown");
        f.setAccessible(true);
        return (JComboBox<String>) f.get(panel);
    }

    private static List<ChannelUserList.Entry> roster() {
        return Arrays.asList(
                new ChannelUserList.Entry("bob", "@", 2),
                new ChannelUserList.Entry("dave", "+", 4),
                new ChannelUserList.Entry("erin", "", Integer.MAX_VALUE));
    }

    @Test
    public void headerReportsTheUserCount() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());
        assertEquals("Users (3)", nickDropdown(panel).getItemAt(0));
    }

    @Test
    public void entriesRenderWithTheirPrefix() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        JComboBox<String> dropdown = nickDropdown(panel);
        assertEquals(4, dropdown.getItemCount());
        assertEquals("@bob", dropdown.getItemAt(1));
        assertEquals("+dave", dropdown.getItemAt(2));
        assertEquals("erin", dropdown.getItemAt(3));
    }

    @Test
    public void headerIsSelectedAfterPopulating() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());
        assertEquals(0, nickDropdown(panel).getSelectedIndex());
    }

    @Test
    public void selectingANickOpensAndFocusesThatPmBuffer() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1); // "@bob"

        assertTrue("a PM buffer for bob should exist", panel.isPane("bob"));
        assertEquals("and it should be focused", "bob", panel.getCurrentChannel());
    }

    @Test
    public void selectingANickStripsThePrefixFromTheBufferName() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1); // "@bob"

        assertTrue(panel.isPane("bob"));
        assertFalse("the prefix is decoration, not part of the nick", panel.isPane("@bob"));
    }

    @Test
    public void selectingANickSnapsBackToTheHeader() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(2); // "+dave"

        assertEquals("action menu, not a persistent selection", 0, nickDropdown(panel).getSelectedIndex());
    }

    @Test
    public void reSelectingTheHeaderAfterAnActionOpensNothingNew() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1);   // opens "bob", then snaps back to the header
        int buffersAfterPick = panel.getChannelNames().size();

        nickDropdown(panel).setSelectedIndex(0);   // the header is not an action

        assertEquals("header selection must not open a buffer", buffersAfterPick, panel.getChannelNames().size());
    }

    @Test
    public void anUpdateForAnUnfocusedChannelDoesNotRedrawTheDropdown() throws Exception {
        IrcPanel panel = panelWith("#chan", "#other");
        panel.setChannelUsers("#chan", roster());                       // #chan is tab 0, focused
        panel.setChannelUsers("#other", Collections.singletonList(
                new ChannelUserList.Entry("zoe", "", Integer.MAX_VALUE)));

        assertEquals("still showing #chan's roster", "Users (3)", nickDropdown(panel).getItemAt(0));
    }

    @Test
    public void dropdownIsDisabledOnNonChannelBuffers() throws Exception {
        IrcPanel panel = panelWith("System");
        panel.setChannelUsers("System", Collections.emptyList());
        assertFalse(nickDropdown(panel).isEnabled());
    }

    @Test
    public void dropdownIsEnabledOnChannelBuffers() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());
        assertTrue(nickDropdown(panel).isEnabled());
    }

    @Test
    public void setChannelUsersBeforeTheGuiExistsDoesNotThrow() {
        IrcPanel panel = new IrcPanel(); // tabbedPane is still null
        panel.setChannelUsers("#chan", roster());
    }

    @Test
    public void anEmptyRosterLeavesOnlyTheHeader() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", Collections.emptyList());

        JComboBox<String> dropdown = nickDropdown(panel);
        assertEquals(1, dropdown.getItemCount());
        assertEquals("Users (0)", dropdown.getItemAt(0));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.IrcPanelNickListTest'
```

Expected: compilation failure — `cannot find symbol: method setChannelUsers`.

- [ ] **Step 3: Add the fields**

In `IrcPanel.java`, next to the existing `bufferDropdown` declaration (line 63), add:

```java
    private static final String USERS_HEADER_PREFIX = "Users (";
    private final Map<String, List<ChannelUserList.Entry>> channelUserSnapshots = new ConcurrentHashMap<>();
    private List<ChannelUserList.Entry> displayedEntries = Collections.emptyList();
    private final JComboBox<String> nickDropdown = getNickComboBox();
```

Add the import:

```java
import java.util.concurrent.ConcurrentHashMap;
```

- [ ] **Step 4: Add the combo box builder and its behavior**

Add these methods near `getBufferComboBox()`:

```java
    private JComboBox<String> getNickComboBox() {
        final JComboBox<String> combo = new JComboBox<>();
        combo.setBackground(Color.DARK_GRAY);
        combo.setForeground(Color.WHITE);
        combo.setPreferredSize(new Dimension(90, 25));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = value == null ? "" : value.toString();
                if (text.startsWith(USERS_HEADER_PREFIX)) {
                    label.setForeground(Color.GRAY);
                } else {
                    label.setForeground(nickColor(text));
                }
                return label;
            }
        });
        combo.addActionListener(e -> openQueryFromNickDropdown());
        return combo;
    }

    /**
     * Colors a dropdown label the same way the chat pane colors that nick. The label carries a
     * prefix character, so the raw nick is taken from the backing entry rather than the text.
     */
    private Color nickColor(String label) {
        for (ChannelUserList.Entry entry : displayedEntries) {
            if (label.equals(entry.getPrefix() + entry.getNick())) {
                try {
                    return Color.decode(ChannelPane.htmlColorById(ChannelPane.nickColorId(entry.getNick())));
                } catch (NumberFormatException ignored) {
                    return Color.WHITE;
                }
            }
        }
        return Color.WHITE;
    }

    /** Opens (or focuses) a PM buffer for the selected nick, then returns to the header. */
    private void openQueryFromNickDropdown() {
        int index = nickDropdown.getSelectedIndex();
        if (index < 1 || index > displayedEntries.size()) {
            return;
        }
        String nick = displayedEntries.get(index - 1).getNick();
        nickDropdown.setSelectedIndex(0);
        addChannel(nick);
        setFocusedChannel(nick);
    }

    /** Pushes a fresh roster in. Only redraws when it is for the buffer currently on screen. */
    public void setChannelUsers(String channel, List<ChannelUserList.Entry> entries) {
        channelUserSnapshots.put(channel, entries);
        if (tabbedPane != null && channel.equals(getCurrentChannel())) {
            repopulateNickDropdown();
        }
    }

    /**
     * Rebuilds the dropdown for the focused buffer. Action listeners are detached for the
     * duration: this runs from inside the dropdown's own listener whenever selecting a nick
     * changes the focused tab.
     */
    private void repopulateNickDropdown() {
        if (tabbedPane == null) {
            return;
        }
        String channel = getCurrentChannel();
        List<ChannelUserList.Entry> entries =
                channelUserSnapshots.getOrDefault(channel, Collections.emptyList());
        displayedEntries = entries;

        ActionListener[] listeners = nickDropdown.getActionListeners();
        for (ActionListener listener : listeners) {
            nickDropdown.removeActionListener(listener);
        }

        nickDropdown.removeAllItems();
        nickDropdown.addItem(USERS_HEADER_PREFIX + entries.size() + ")");
        for (ChannelUserList.Entry entry : entries) {
            nickDropdown.addItem(entry.getPrefix() + entry.getNick());
        }
        nickDropdown.setSelectedIndex(0);
        nickDropdown.setEnabled(channel != null && channel.startsWith("#"));

        for (ActionListener listener : listeners) {
            nickDropdown.addActionListener(listener);
        }
    }
```

- [ ] **Step 5: Place it in the control row and hook repopulation**

In `initializeGui()`, add the dropdown to `row2` **before** `bufferDropdown` so `FlowLayout.RIGHT` puts it on the left. Replace `row2.add(bufferDropdown);` (line 162) with:

```java
        row2.add(nickDropdown);
        row2.add(bufferDropdown);
```

In the `tabbedPane.addChangeListener` block at the end of `initializeGui()`, add a repopulate call so switching buffers redraws the list. The listener becomes:

```java
        tabbedPane.addChangeListener(e -> {
            String newChannel = getCurrentChannel();
            if (newChannel != null && unreadMessages.containsKey(newChannel)) {
                unreadMessages.put(newChannel, false);
                int selectedIndex = tabbedPane.getSelectedIndex();
                if (selectedIndex != -1) {
                    tabbedPane.setForegroundAt(selectedIndex, Color.WHITE);
                }
            }
            repopulateNickDropdown();
        });
```

In `removeChannel(String channel)`, drop the stale snapshot. After `bufferDropdown.removeItem(channel);` add:

```java
        channelUserSnapshots.remove(channel);
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test --tests 'com.irc.IrcPanelNickListTest'
```

Expected: PASS, 12 tests.

- [ ] **Step 7: Run the full suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test
```

Expected: PASS, including `IrcPanelRenameTest` and `IrcPanelConcurrencyTest`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/irc/IrcPanel.java src/test/java/com/irc/IrcPanelNickListTest.java
git commit -m "feat: Nicklist dropdown that opens PMs"
```

---

### Task 6: Bridge the adapter

**Files:**
- Modify: `src/main/java/com/irc/IrcAdapter.java`

**Interfaces:**
- Consumes: `SimpleIrcClient.IrcEvent.Type.USERS_CHANGED` and `getChannelUsers` (Task 4); `IrcPanel.setChannelUsers` (Task 5).
- Produces: nothing — this is the final connection.

**Why there is no new test file:** this task is pure wiring between two layers that are each already covered. The verification is the full suite plus a manual smoke test, which is the last step.

**Note:** `IrcAdapter` already holds a `panel` field, so nothing needs threading through `IrcPlugin`. `IrcPlugin.java` is not modified in this task or any other.

- [ ] **Step 1: Delete the dead map**

Remove line 23 entirely:

```java
    private final Map<String, Set<String>> channelUsers = new HashMap<>();
```

It is declared and never read or written — confirm before deleting:

```bash
grep -n 'channelUsers' src/main/java/com/irc/IrcAdapter.java
```

Expected: only line 23.

Then remove the imports it orphaned (lines 9, 10, 12):

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
```

and add the one the next step needs:

```java
import java.util.List;
```

- [ ] **Step 2: Forward the event**

In the `client.addEventListener(...)` switch (the one containing `case NAMES:` around line 315), add:

```java
                case USERS_CHANGED:
                    if (panel != null) {
                        String usersChannel = event.getTarget();
                        // Snapshot on the IRC thread; it is immutable, so the EDT can hold it.
                        List<ChannelUserList.Entry> users = client.getChannelUsers(usersChannel);
                        SwingUtilities.invokeLater(() -> panel.setChannelUsers(usersChannel, users));
                    }
                    break;
```

- [ ] **Step 3: Compile and run the full suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew test
```

Expected: PASS, every test across all files.

- [ ] **Step 4: Manual smoke test**

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
./gradlew run
```

Verify in the running client:

1. Join a channel — the dropdown reads `Users (N)` with the right count.
2. Ops show `@`, voiced users show `+`, and they sort above unprivileged users.
3. Selecting a nick opens a PM buffer for that nick and focuses it; the dropdown returns to `Users (N)`.
4. Switching buffers swaps the roster; on `System` the dropdown is greyed out and reads `Users (0)`.
5. Someone joining or leaving updates the count live.
6. **Check `row2` did not wrap to two rows.** If the nick and buffer dropdowns overflow the ~225px panel width, reduce the `90` in `combo.setPreferredSize(new Dimension(90, 25))` until they fit on one line.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/irc/IrcAdapter.java
git commit -m "feat: Push channel rosters to the nicklist"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| `ModeSpec` (PREFIX/CHANMODES/defaults) | 1 |
| `ChannelUserList` (all operations, sorting, thread safety) | 2 |
| Nick colors (`nickColorId`, rows 64–87, `floorMod`) | 3 |
| `SimpleIrcClient` (005, 353, 366, MODE, JOIN/PART/QUIT/KICK/NICK, `USERS_CHANGED`, `leaveChannel`, disconnect) | 4 |
| `IrcPanel` (placement, header, renderer, action, repopulation, cleanup) | 5 |
| `IrcAdapter` (dead field, event forwarding) | 6 |
| "`IrcPlugin` requires no changes" | Confirmed — no task touches it |

**Type consistency:** `ChannelUserList.Entry` is constructed in tests as `new Entry(nick, prefix, rank)` matching Lombok's `@Value` all-args constructor and read via `getNick()`/`getPrefix()`/`getRank()`. `getChannelUsers` returns `List<ChannelUserList.Entry>` in Tasks 4, 5 and 6 alike. `nickColorId` returns a palette *code* everywhere, never a hex string — callers always wrap it in `htmlColorById`.

**Deliberate deviation from the spec:** the spec described stripping the prefix character from the dropdown label. Task 5 indexes into `displayedEntries` instead, which is prefix-agnostic and keeps `IrcPanel` free of any prefix table. Same observable behavior, less duplicated knowledge.

**Verified against the real build while writing this plan** (rather than assumed):

- `"polygenelubricants".hashCode() == Integer.MIN_VALUE` — a valid fixture for the `floorMod` crash test.
- `htmlColorById` returns a `#RRGGBB` string for every code in the 36-colour palette (`"white"`/`"black"` are only ever returned for codes `00`/`01` and the out-of-range fallback), so `Color.decode` in the renderer is safe.
- `IrcConfig` has exactly two abstract methods, `username()` and `password()`; everything else is `default`. A two-method anonymous stub compiles.
- Driving `addChannel` on a stub-configured panel keeps `channelPanes` aligned with `tabbedPane` — a scratch run produced `channelNames = [#chan, bob]` with `getCurrentChannel()` correctly reporting `bob`.
- `config.channel()` defaults to `#rshelp`, which auto-focuses on add; fixtures avoid that name.
