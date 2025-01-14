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

Received private messages will appear prefixed with `(pm)`.

Sending a private message: `;;msg <nick> <message>`

Example: `;;msg foobar Thanks for the information`

### Notices

Notices will appear prefixed with `(notice)` to indicate it was not a channel message.

Sending a notice: `;;notice <nick> <message>`

Example: `;;notice foobar Hey there!`

### Network Services

The following services run on SwiftIRC: NickServ, ChanServ, BotServ, and HostServ.

You may communicate via the following commands with the respective service:

```text
NickServ: ;;ns
ChanServ: ;;cs
BotServ: ;;bs
HostServ: ;;hs
```

### Miscellaneous Commands

Change user modes: `;;umode +R` (as an example)

Change channel modes: `;;mode #rshelp -s`

Change the topic: `;;topic #rshelp Hi this was a bad idea`

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

## Known Issues

* Various output is hidden, such as channel and user modes, topics, and other raw server output.
