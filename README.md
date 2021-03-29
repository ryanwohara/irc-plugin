# ![Logo](logo.png) IRC Plugin

An integration with SwiftIRC through the OSRS chat box.

## Functionality

### Channel Messages

Channel messages will show up in the chat box starting with the username that sent the message.

Sending a channel message: `//words can go here`

Example: `//Hello #rshelp!`

### Private Messages

Received private messages will appear prefixed with `(pm)`.

Sending a private message: `///msg <nick> <message>`

Example: `///msg foobar Thanks for the information`

### Notices

Notices will appear prefixed with `(notice)` to indicate it was not a channel message.

Sending a notice: `///notice <nick> <message>`

Example: `///notice foobar Hey there!`

### Network Services

The following services run on SwiftIRC: NickServ, ChanServ, BotServ, and HostServ.

You may communicate via the following commands with the respective service:

```
NickServ: /ns
ChanServ: /cs
BotServ: /bs
HostServ: /hs
```

## Configuration

### username

The username used to connect to SwiftIRC.

### channel (optional)

The channel you intend to join. Leaving this blank will default to #rshelp.

### password (optional)

The password to identify with NickServ.
