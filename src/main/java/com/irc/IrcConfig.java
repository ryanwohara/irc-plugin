/*
 * Copyright (c) 2020, Ryan W. O'Hara <ryan@ryanwohara.com>, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.irc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.ChatMessageType;
import net.runelite.client.config.*;
import net.runelite.client.ui.ColorScheme;

import java.awt.Color;


@ConfigGroup("irc")
public interface IrcConfig extends Config
{
    @ConfigSection(
            name = "Connection",
            description = "Connection settings",
            position = 0
    )
    String connectionSettings = "connectionSettings";

    @ConfigItem(
            keyName = "server",
            name = "Server",
            description = "Server to use to directly connect.",
            position = 0,
            section = connectionSettings
    )
    default Server server() {
        return Server.USA;
    };

    @Getter
    @RequiredArgsConstructor
    enum Server {
        // Single-label names under swiftirc.net: the servers' certificate covers
        // *.swiftirc.net, and a wildcard matches exactly one label, so the older
        // fiery.ca.us / tardis.en.uk forms fail hostname verification. Same hosts, same IPs.
        USA("Fiery (West-USA)", "fiery.swiftirc.net"),
        UK("London (UK)", "tardis.swiftirc.net");

        private final String name;
        private final String hostname;
    }

    @ConfigItem(
            keyName = "username",
            name = "Username",
            description = ";use the chat like this.",
            position = 1,
            section = connectionSettings
    )
    String username();

    @ConfigItem(
            keyName = "accountName",
            name = "Account Name (Optional)",
            description = "NickServ account to identify against, if different from your nick. Leave blank to identify by nick.",
            position = 2,
            section = connectionSettings
    )
    default String accountName()
    {
        return "";
    }

    @ConfigItem(
            keyName = "password",
            name = "Password (Optional) (not Jagex)",
            description = "NickServ password (Optional) (NEVER your RS password!)",
            position = 3,
            secret = true,
            section = connectionSettings
    )
    String password();

    @ConfigItem(
            keyName = "channel",
            name = "Channel(s)",
            description = "Channel(s) to join, comma separated",
            position = 4,
            section = connectionSettings
    )
    default String channel()
    {
        return "#rshelp";
    }

    @ConfigItem(
            keyName = "channelPassword",
            name = "Channel Password",
            description = "Password to enter channel. (Optional)",
            position = 5,
            secret = true,
            section = connectionSettings
    )
    default String channelPassword()
    {
        return "";
    }

    @ConfigSection(
            name = "General",
            description = "General settings",
            position = 1
    )
    String generalSettings = "generalSettings";

    @ConfigItem(
            keyName = "prefix",
            name = "Prefix",
            description = ";chat with this character like this.",
            position = 0,
            section = generalSettings
    )
    default String prefix() { return ";"; }

    @ConfigItem(
            keyName = "activeChannelOnly",
            name = "Active Channel Only",
            description = "Only show the active IRC channel in the OSRS chat box.",
            position = 1,
            section = generalSettings
    )
    default boolean activeChannelOnly() { return false; }

    @ConfigItem(
            keyName = "backTickNavigation",
            name = "Backtick Channel Navigation",
            description = "Use ` and shift+` to navigate channels",
            position = 2,
            section = generalSettings
    )
    default boolean backTickNavigation() { return true; }

    @ConfigItem(
            keyName = "pageUpDownNavigation",
            name = "Page Up/Down Channel Navigation",
            description = "Use PageUp/PageDn and shift+PageUp/shift+PageDn to navigate channels",
            position = 3,
            section = generalSettings
    )
    default boolean pageUpDownNavigation() { return true; }

    @ConfigItem(
            keyName = "autofocusOnNewTab",
            name = "Autofocus on New Tab",
            description = "If you receive a PM/notice or join a new channel, it will become your focus. Initial channel join will always focus regardless of this setting.",
            position = 4,
            section = generalSettings
    )
    default boolean autofocusOnNewTab() { return false; }

    @ConfigItem(
            keyName = "filterServerNotices",
            name = "Server Notice Tab",
            description = "Receiving a server notice will open a new dedicated tab for it.",
            position = 5,
            section = generalSettings
    )
    default boolean filterServerNotices() { return false; }

    @Getter
    @RequiredArgsConstructor
    enum Chatbox {
        FRIENDSCHAT(ChatMessageType.FRIENDSCHAT),
        CLAN_CHAT(ChatMessageType.CLAN_CHAT);

        private final ChatMessageType type;
    }

    @ConfigItem(
            keyName = "chatboxType",
            name = "Chatbox Type",
            description = "Which type of chatbox will be used in-game.",
            position = 6,
            section = generalSettings
    )
    default Chatbox getChatboxType() { return Chatbox.FRIENDSCHAT; }

    @Getter
    @RequiredArgsConstructor
    enum MessageDisplay {
        Status("Show only in status window."),
        Current("Show only in current window."),
        Private("Show only in a private window with the sender.");

        private final String description;
    }

    @ConfigItem(
            keyName = "filterNotices",
            name = "Notice Window",
            description = "Adjust how to treat the display of notices.",
            position = 7,
            section = generalSettings
    )
    default MessageDisplay filterNotices() { return MessageDisplay.Current; }


    @ConfigItem(
            keyName = "filterPMs",
            name = "PM Window",
            description = "Adjust how to treat the display of PMs.",
            position = 8,
            section = generalSettings
    )
    default MessageDisplay filterPMs() { return MessageDisplay.Current; }

    @ConfigItem(
            keyName = "hideConnectionMessages",
            name = "Hide Join/Part/Quit/Kick",
            description = "Hides connection status messages like joins, parts, quits, and kicks from channel windows.",
            position = 9,
            section = generalSettings
    )
    default boolean hideConnectionMessages() { return false; }

    @ConfigItem(
            keyName = "logRawLines",
            name = "Log Raw IRC Lines",
            description = "Writes every IRC line sent and received to the RuneLite client log, "
                    + "with passwords removed. Only turn this on to diagnose a connection problem.",
            position = 10,
            section = generalSettings
    )
    default boolean logRawLines() { return false; }

    @ConfigItem(
            keyName = "inGameTextColorEnabled",
            name = "Custom In-Game Text Colour",
            description = "Colour IRC messages in the in-game chat box with the colour below. "
                    + "Off, they follow RuneLite's Chat Colour settings.",
            position = 11,
            section = generalSettings
    )
    default boolean inGameTextColorEnabled() { return false; }

    @ConfigItem(
            keyName = "inGameTextColor",
            name = "In-Game Text Colour",
            description = "Colour of IRC messages in the in-game chat box, when Custom In-Game Text Colour is on.",
            position = 12,
            section = generalSettings
    )
    default Color inGameTextColor() { return ColorScheme.BRAND_ORANGE; }

    @ConfigItem(
            keyName = "ircColorsInGame",
            name = "IRC Colours In-Game",
            description = "Show IRC colour codes in the in-game chat box instead of stripping them. "
                    + "Background colours are not shown; the chat box cannot draw them.",
            position = 13,
            section = generalSettings
    )
    default boolean ircColorsInGame() { return true; }

    @ConfigSection(
            name = "Overlay",
            description = "In-game overlay",
            position = 2
    )
    String overlaySettings = "overlaySettings";

    @ConfigItem(
            keyName = "overlayEnabled",
            name = "Enable In-Game Overlay",
            description = "Show the IRC overlay inside the game",
            position = 0,
            section = overlaySettings
    )
    default boolean overlayEnabled() { return true; }

    @ConfigItem(
            keyName = "overlayDynamic",
            name = "Overlay the Chat Box",
            description = "Toggle between a chatbox overlay or an alt+click & drag overlay.",
            position = 1,
            section = overlaySettings
    )
    default boolean overlayDynamic() { return true; }

    @ConfigItem(
            keyName = "overlayMaxWidth",
            name = "Overlay Max Width",
            description = "Max width of the overlay",
            position = 2,
            section = overlaySettings
    )
    default int overlayMaxWidth() { return 500; }

    @ConfigSection(
            name = "Side Panel",
            description = "Side panel settings",
            position = 3
    )
    String sidePanelSettings = "sidePanelSettings";

    @ConfigItem(
            keyName = "sidePanel",
            name = "Enabled",
            description = "Show IRC in the sidebar",
            position = 0,
            section = sidePanelSettings
    )
    default boolean sidePanel() { return true;}

    @ConfigItem(
            keyName = "popOut",
            name = "Pop out window",
            description = "Move IRC into a resizable, interactive window. Closing it returns IRC to the sidebar.",
            position = 8,
            hidden = true,
            section = sidePanelSettings
    )
    default boolean popOut() { return false; }

    @ConfigItem(
            keyName = "popOutAlwaysOnTop",
            name = "Keep pop-out on top",
            description = "Keep the IRC pop-out above other windows",
            position = 9,
            section = sidePanelSettings
    )
    default boolean popOutAlwaysOnTop() { return false; }

    @ConfigItem(
            keyName = "timestamp",
            name = "Timestamp",
            description = "Enable the timestamp",
            position = 1,
            section = sidePanelSettings
    )
    default boolean timestamp() { return true;}

    @ConfigItem(
            keyName = "hoverPreviewImages",
            name = "Hover-Preview Image Links",
            description = "Display an image just by hovering over the link (WARNING: could leak your IP without clicking)",
            position = 2,
            section = sidePanelSettings
    )
    default boolean hoverPreviewImages() { return false; }

    @ConfigItem(
            keyName = "colorizedNicks",
            name = "Colorized Nicks",
            description = "Add color to nicks.",
            position = 3,
            section = sidePanelSettings
    )
    default boolean colorizedNicks() { return true; }

    @Range(
            min = 0
    )
    @ConfigItem(
            keyName = "panelPriority",
            name = "Position in Sidebar",
            description = "Control where the panel appears in the sidebar of RuneLite",
            position = 4,
            section = sidePanelSettings
    )
    default int getPanelPriority() { return 10; }

    @Range(
            min = 0
    )
    @ConfigItem(
            keyName = "maxScrollback",
            name = "Maximum Scrollback per Channel",
            description = "Restrict the scrollback per channel to avoid lag",
            position = 5,
            section = sidePanelSettings
    )
    default int getMaxScrollback() { return 100; }

    @ConfigItem(
            keyName = "fontFamily",
            name = "Font Family",
            description = "Font family to use everywhere.",
            position = 6,
            hidden = true,
            section = sidePanelSettings
    )
    default String fontFamily() { return "SansSerif"; }

    @Range(
            min = 8,
            max = 32
    )
    @ConfigItem(
            keyName = "fontSize",
            name = "Font Size",
            description = "Font size for the side panel and pop-out window.",
            position = 7,
            section = sidePanelSettings
    )
    default int fontSize() { return 12; }

    @ConfigItem(
            keyName = "chatBackgroundColor",
            name = "Chat Background",
            description = "Background colour of the chat area in the side panel and pop-out window.",
            position = 10,
            section = sidePanelSettings
    )
    default Color chatBackgroundColor() { return ColorScheme.DARKER_GRAY_COLOR; }

    @ConfigItem(
            keyName = "chatTextColor",
            name = "Chat Text Colour",
            description = "Colour of regular chat messages. Joins, parts, notices and IRC colour codes keep their own colours.",
            position = 11,
            section = sidePanelSettings
    )
    default Color chatTextColor() { return ColorScheme.LIGHT_GRAY_COLOR; }

    @ConfigItem(
            keyName = NetworkStore.CONFIG_KEY,
            name = "Networks",
            description = "Extra IRC networks, edited from the pop-out's Networks dialog.",
            hidden = true
    )
    default String networks() { return ""; }

    @ConfigItem(
            keyName = OrderStore.NETWORK_ORDER_KEY,
            name = "Network order",
            description = "Comma-separated network ids, in the order the pop-out lists them.",
            hidden = true
    )
    default String networkOrder() { return ""; }

    @ConfigItem(
            keyName = OrderStore.CHANNEL_ORDER_KEY,
            name = "Channel order",
            description = "Each network's channels, in the order the pop-out lists them.",
            hidden = true
    )
    default String channelOrder() { return ""; }

    @ConfigItem(
            keyName = "swiftIrcEnabled",
            name = "Connect to SwiftIRC automatically",
            description = "Off when SwiftIRC is reached another way, e.g. through a ZNC. Set in the Networks dialog.",
            hidden = true
    )
    default boolean swiftIrcEnabled() { return true; }

    @ConfigItem(
            keyName = "inGameNetwork",
            name = "In-game network",
            description = "The network whose chat is echoed into the game chatbox. Set in the Networks dialog.",
            hidden = true
    )
    default String inGameNetwork() { return NetworkConfig.SWIFTIRC_ID; }
}
