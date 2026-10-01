# ![Logo](icon.png) IRC Plugin

An integration with SwiftIRC through the OSRS chat box.

## Warning

This will share your IP with the SwiftIRC administration.

## Functionality

It is ideal to set the prefix to a character you can easily prefix IRC messages.

The default prefix is `;`. This document will use the default as reference.

### Channel Messages

Channel messages will show up in the chat box starting with the username that sent the message.

Sending a channel message: `;words can go here`

Example: `;Hello #rshelp!`

### Private Messages

Sending a private message: `;;msg <nick> <message>`

Example: `;;msg foobar Thanks for the information`

### Notices

Sending a notice: `;;notice <nick> <message>`

Example: `;;notice foobar Hey there!`

### Network Services

The following services run on SwiftIRC: NickServ, ChanServ, BotServ, HostServ, and MemoServ.

You may communicate via the following commands with the respective service:

```text
BotServ: ;;bs
ChanServ: ;;cs
HostServ: ;;hs
MemoServ: ;;ms
NickServ: ;;ns
NickServ Identification: ;;id
```

### Miscellaneous Commands

Commands work from the chat box with the prefix doubled (`;;join`), or from the side panel's
input box with a slash (`/join`). `;;help` lists them all.

Join a channel: `;;join #cooking [key]` (with no channel, opens the channel browser)

Leave a channel: `;;part #cooking` (also `;;leave`; the current channel if omitted)

Browse the server's channel list: `;;list` (filter it, e.g. `;;list >50` for channels with more
than 50 users)

Change the focused channel: `;;go rsh`

Send an action: `;;me waves`

Look up a user: `;;whois foobar`

Set or clear your away status: `;;away brb` / `;;away`

Change your nick: `;;nick foo bar` (spaces become underscores: `foo_bar`)

List the users in the current channel: `;;names`

View or set the topic: `;;topic` / `;;topic #rshelp New topic`

Change channel modes: `;;mode #rshelp -s`

Change user modes: `;;umode +R`

Clear the side panel: `;;clear`

Open IRC in its own window: `;;popout`

Disconnect: `;;quit [message]`

## Configuration

### Connection

#### server

Fiery (West-USA) or London (UK).

More details are available at SwiftIRC.net:
https://swiftirc.net/info/

#### username

The username used to connect to SwiftIRC.

#### account name (optional)

The NickServ account to identify against, if it differs from your nick. Leave blank to identify
by nick.

#### password (optional)

The password to identify with NickServ. Never your RuneScape password.

#### channel(s) (optional)

The channel(s) you intend to join, comma separated. Leaving this blank will default to #rshelp.

#### channel password (optional)

The key for a channel that requires one.

### General

#### prefix

Defaults to `;`. Prefixed to messages destined for IRC.

#### active channel only

Only show the active channel messages in the OSRS chat box. The "active channel" is the active tab in the side panel.

#### backtick channel navigation

In game, press `` ` `` to switch to the next channel and `` shift+` `` for the previous one. On by
default.

#### page up/down channel navigation

In game, press Page Down to switch to the next channel and Page Up for the previous one. On by
default.

#### autofocus on new tab

When a new tab opens, autofocus on it. This means sending/receiving a notice/message to a target, without an open tab,
will change the active channel to this target.

#### server notice tab

Server notices will default to the System tab; you can optionally allow them to create their own tab.

#### chatbox type

Which in-game chat box IRC messages appear in: Friends Chat (default) or Clan Chat.

#### notice window / PM window

Where notices and private messages are shown: in the current window (default), in the System
tab, or in a private window with the sender.

#### hide join/part/quit/kick

Hide joins, parts, quits, and kicks from channel windows.

#### log raw IRC lines

Writes every IRC line sent and received to the RuneLite client log. Off by default. Turn it on
only when you are chasing a connection problem, then turn it back off - it is noisy.

Credentials are stripped before anything is written, so the log is safe to attach to a bug report:
the SASL exchange, server passwords, NickServ and ChanServ commands that carry a password, and
channel keys (from `JOIN`, from `MODE +k`, and from the server's own reply when you join a keyed
channel). What remains is the protocol traffic itself.

#### custom in-game text colour / in-game text colour

Colour IRC messages in the in-game chat box with a colour of your choice. Off by default, in
which case they follow RuneLite's Chat Colour settings.

#### IRC colours in-game

Show IRC colour codes in the in-game chat box instead of stripping them. On by default.
Background colours are not shown, as the chat box cannot draw them.

### Overlay

#### enable in-game overlay

Show the IRC overlay inside the game. On by default.

#### overlay the chat box

Place the overlay over the chat box (default), or turn this off for an overlay you can move with
alt+click & drag.

#### overlay max width

Maximum width of the overlay, in pixels. Defaults to 500.

### Side Panel

#### enabled

Whether IRC's panel will appear in the sidebar. The pop-out window works either way.

#### pop out window

Click the **^** button in the IRC sidebar panel, or type `;;popout` in the chat box, to move IRC
into a separate, resizable window. `;;popout` works even when **Enabled** is off, and brings the
window to the front if it is already open.
Drag its title bar to move it, including to another monitor. All chat controls remain
interactive: type messages, switch channels, browse channels, and click links as usual.
The expanded layout includes a channel/private-chat tree with unread markers, chat in the
center, and a user list on the right. Drag the dividers to adjust column widths. Clicking a
channel in the tree moves the cursor to the input box so you can type straight away.
Channels are numbered in the tree, matching the **Alt+number** shortcuts (see
[Keyboard Shortcuts](#keyboard-shortcuts)). Double-click
a nick (or press Enter on it) to open a private conversation; right-click for Message and WHOIS.
The toolbar provides Reconnect, Join, Leave, Channels, and Dock actions. Docking restores the
compact sidebar layout.
The same chat controls are reused, preserving scrollback, the selected channel, and unsent text
without reconnecting to IRC.

Click **Dock**, or close the window with its **X**, to return IRC to the RuneLite
sidebar. Click the IRC sidebar icon to view it there. If **Enabled** is off, docking just closes
the window. Window size and position are remembered while the plugin remains enabled.
Disabling the plugin also closes the pop-out.

#### keep pop-out on top

Optionally keep the pop-out above other windows. Off by default.

#### timestamp

Prefix messages with a timestamp in the format of `[hour:minute:second]`.

#### hover-preview image links

Enable to preview image links by hovering your mouse over them. WARNING: this will make it easier to share your IP with
an image host.

#### colorized nicks

Add a color to nicks appearing in the side panel.

#### position in sidebar

Specifically where the side panel appears on the right, from top to bottom.

#### maximum scrollback per channel

How many messages each channel keeps, to avoid lag. Defaults to 100.

#### font family / font size

The font IRC uses, and its size in the side panel and pop-out window. The pop-out also has a font
selector in its toolbar.

#### chat background / chat text colour

The background colour of the chat area, and the colour of regular chat messages. Joins, parts,
notices, and IRC colour codes keep their own colours.

## Screenshots

![sidepanel.png](sidepanel.png)
![chatbox.png](chatbox.png)
![popout.png](popout.png)


## Guide

### Keyboard Shortcuts

In the side panel or pop-out window:

| Keys | Action |
| --- | --- |
| **Alt+1** … **Alt+9** | Jump to channels 1–9 |
| **Alt+0** | Jump to channel 10 |
| **Alt+J**, then two digits | Jump to any channel, e.g. **Alt+J 1 1** for the eleventh |
| **Tab** / **Shift+Tab** | Complete a nick or channel name |
| **↑** / **↓** | Recall previously sent messages |
| **Ctrl+B** / **Ctrl+I** / **Ctrl+U** | Insert bold / italic / underline formatting |
| **Ctrl+K** | Insert a colour code |

On macOS, use **Option** for **Alt** and **Cmd** for **Ctrl**. Channel numbers follow the
pop-out's channel tree, or the tab order in the side panel.

In game, `` ` `` / `` shift+` `` and Page Up / Page Down switch channels (see the settings above).

### Browsing Channels

Run `;;list`, or `;;join` with no channel, to open the channel browser. Filter it by name or
topic, sort by any column, and double-click a channel (or select it and press Enter or **Join**) to
join it. The **Channels** button in the pop-out opens the same browser.

### Multiple Channels

Auto-joining multiple channels is possible by comma separating them in the settings:
```text
#rshelp,#swiftirc,#cooking
```

### Registering a Nick

To run commands, it is recommended to use the `System` tab of the side panel.

Once there, it is possible to register your nick and email (where the email is primarily required to reset a lost password):
```text
/ns register <password> <email>
```

You can now save the password in the settings for the plugin. This will automatically identify you when you connect.

Once you have registered your nick, you can join registered-only channels and register new channels.

### Registering a Channel

These commands will create and register a new channel:
```text
/join #secret-new-channel
/cs register #secret-new-channel
```

### IRC Help

You can ask in `#irchelp` for IRC-specific questions.
```text
/join #irchelp
```

## Troubleshooting Connection Problems

The plugin reports why a connection failed rather than leaving you to guess. When something goes
wrong you should see one of:

- **A reason from the server.** `Server closed the link: Closing Link: you[1.2.3.4] (Ping timeout:
  240 seconds)`, `Killed by <operator>: <reason>`, or `Server error 465: You are banned from this
  server`. This is the server's own text - it is the most useful thing to include in a report.
- **A reason from the network.** `DNS lookup failed: irc.swiftirc.net could not be resolved`, or
  `Failed while connecting to irc.swiftirc.net:6697 - ConnectException: Connection refused`. The
  message names what was being attempted (connecting, the TLS handshake, registering, or reading
  from an established connection) so a firewall problem is distinguishable from a server problem.
- **A silent close.** `Connection closed: server closed the connection during registration,
  without saying why` usually means the server dropped you without explanation - a ban or a
  connection throttle.

Disconnect messages carry the cause where one is known, so `Disconnected from IRC (Ping timeout:
240 seconds)` tells you it was not your own `/quit`.

If a problem is not reproducible, enable **log raw IRC lines** in the plugin settings and
reproduce it. The full protocol exchange, with passwords removed, is written to the RuneLite
client log. The setting takes effect immediately, so you can turn it on before reconnecting.
