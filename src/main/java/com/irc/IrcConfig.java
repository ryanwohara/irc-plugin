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
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;


@ConfigGroup("irc")
public interface IrcConfig extends Config
{
    @ConfigItem(
            keyName = "server",
            name = "Server",
            description = "Server to use to directly connect.",
            position = 0
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
            position = 1
    )
    String username();

    @ConfigItem(
            keyName = "password",
            name = "Password (Optional) (not RS)",
            description = "NickServ password (Optional) (NEVER your RS password!)",
            position = 2,
            secret = true
    )
    String password();

    @ConfigItem(
            keyName = "channel",
            name = "Channel",
            description = "Channel to join",
            position = 3
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
            secret = true
    )
    default String channelPassword()
    {
        return "";
    }

    @ConfigItem(
            keyName = "prefix",
            name = "Prefix",
            description = ";chat with this character like this.",
            position = 5
    )
    default String prefix() { return ";"; }

    @ConfigItem(
            keyName = "fontFamily",
            name = "Font Family",
            description = "Font family to use everywhere.",
            position = 6,
            hidden = true
    )
    default String fontFamily() { return "SansSerif"; }

    @ConfigItem(
            keyName = "fontSize",
            name = "Font Size",
            description = "Font size to use everywhere.",
            position = 7,
            hidden = true
    )
    default Integer fontSize() { return 12; }

    @ConfigItem(
            keyName = "sidePanel",
            name = "Side Panel",
            description = "Enable the side panel",
            position = 8
    )
    default boolean sidePanel() { return true;}

    @ConfigItem(
            keyName = "timestamp",
            name = "Timestamp",
            description = "Enable the timestamp",
            position = 9
    )
    default boolean timestamp() { return true;}
}