# ![Logo](icon.png) IRC Plugin

An integration with SwiftIRC through the OSRS chat box.

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
NickServ: ;;ns
ChanServ: ;;cs
BotServ: ;;bs
HostServ: ;;hs
MemoServ: ;;ms
```

### Miscellaneous Commands

Change user modes: `;;umode +R` (as an example)

Change channel modes: `;;mode #rshelp -s`

View the topic: `;;topic`

Clear the side panel: `;;clear`

## Configuration

### username

The username used to connect to SwiftIRC.

### channel (optional)

The channel you intend to join. Leaving this blank will default to #rshelp.

### password (optional)

The password to identify with NickServ.

### prefix

Defaults to `;`. Prefixed to messages destined for IRC.