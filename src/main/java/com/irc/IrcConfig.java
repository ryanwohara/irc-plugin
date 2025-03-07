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


@ConfigGroup("irc")
public interface IrcConfig extends Config
{
    @ConfigSection(
            name = "Connection",
            description = "Connection settings",
            position = 0,
            closedByDefault = true
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
        USA("Fiery (West-USA)", "fiery.ca.us.swiftirc.net"),
        UK("London (UK)", "tardis.en.uk.swiftirc.net");

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
            keyName = "password",
            name = "Password (Optional) (not Jagex)",
            description = "NickServ password (Optional) (NEVER your RS password!)",
            position = 2,
            secret = true,
            section = connectionSettings
    )
    String password();

    @ConfigItem(
            keyName = "channel",
            name = "Channel",
            description = "Channel to join",
            position = 3,
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
            position = 4,
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
            position = 1,
            closedByDefault = false
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
            keyName = "autofocusOnNewTab",
            name = "Autofocus on New Tab",
            description = "If you receive a PM/notice or join a new channel, it will become your focus. Initial channel join will always focus regardless of this setting.",
            position = 3,
            section = generalSettings
    )
    default boolean autofocusOnNewTab() { return false; }

    @ConfigItem(
            keyName = "filterServerNotices",
            name = "Server Notice Tab",
            description = "Receiving a server notice will open a new dedicated tab for it.",
            position = 4,
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
            position = 5,
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
            position = 5,
            section = generalSettings
    )
    default MessageDisplay filterNotices() { return MessageDisplay.Current; }


    @ConfigItem(
            keyName = "filterPMs",
            name = "PM Window",
            description = "Adjust how to treat the display of PMs.",
            position = 6,
            section = generalSettings
    )
    default MessageDisplay filterPMs() { return MessageDisplay.Current; }

    @ConfigSection(
            name = "Side Panel",
            description = "Side panel settings",
            position = 2,
            closedByDefault = true
    )
    String sidePanelSettings = "sidePanelSettings";

    @ConfigItem(
            keyName = "sidePanel",
            name = "Enabled",
            description = "Enable the side panel",
            position = 0,
            section = sidePanelSettings
    )
    default boolean sidePanel() { return true;}

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

    @ConfigItem(
            keyName = "fontSize",
            name = "Font Size",
            description = "Font size to use everywhere.",
            position = 7,
            hidden = true,
            section = sidePanelSettings
    )
    default Integer fontSize() { return 12; }
}