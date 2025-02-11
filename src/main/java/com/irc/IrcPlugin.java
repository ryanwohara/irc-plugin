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

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.*;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.irc.core.IRCClient;
import com.irc.core.IrcListener;
import net.runelite.client.task.Schedule;

import javax.inject.Inject;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.awt.image.BufferedImage;

@PluginDescriptor(
        name = "IRC",
        description = "Integrates IRC with the OSRS chatbox"
)
@Slf4j
public class IrcPlugin extends Plugin implements IrcListener {
    @Inject
    private IrcConfig ircConfig;

    @Inject
    private Client client;

    @Inject
    private ChatMessageManager chatMessageManager;

    @Inject
    private ClientToolbar clientToolbar;
    private IrcPanel panel;
    private NavigationButton uiButton;

    private IRCClient IRCClient;

    @Override
    protected void startUp() {
        connect();

        if (ircConfig.sidepanel()) {
            startIrcPanel();
        }
    }

    @Override
    protected void shutDown() {
        if (IRCClient != null) {
            IRCClient.close();
            IRCClient = null;
        }

        stopIrcPanel();
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    private synchronized void connect() {
        if (IRCClient != null) {
            log.debug("Terminating IRC client {}", IRCClient);
            IRCClient.close();
            IRCClient = null;
        }

        if (!Strings.isNullOrEmpty(ircConfig.username())) {
            String channel;

            if (Strings.isNullOrEmpty(ircConfig.channel())) {
                channel = "#rshelp";
            } else {
                channel = ircConfig.channel().toLowerCase();
                if (!channel.startsWith("#")) {
                    channel = "#" + channel;
                }

                if (channel.contains(",")) {
                    channel = channel.split(",")[0];
                }

            }

            log.debug("Connecting to IRC as {}", ircConfig.username());

            IRCClient = new IRCClient(
                    this,
                    ircConfig.username(),
                    channel,
                    ircConfig.password(),
                    ircConfig.prefix()
            );
            IRCClient.start();
        }
    }

    @Schedule(period = 30, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void checkClient() {
        if (IRCClient != null) {
            if (IRCClient.isConnected()) {
                IRCClient.pingCheck();
            }

            if (!IRCClient.isConnected()) {
                log.debug("Reconnecting...");

                connect();
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged configChanged) {
        if (!configChanged.getGroup().equals("irc")) {
            return;
        }

        stopIrcPanel();
        if (ircConfig.sidepanel()) {
            startIrcPanel();
        }
    }

    private void addChatMessage(String sender, String message) {
        String chatMessage = new ChatMessageBuilder()
                .append(ChatColorType.NORMAL)
                .append(message)
                .build();

        if (client.getGameState() == GameState.LOGGED_IN) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.FRIENDSCHAT)
                    .sender("IRC")
                    .name(sender)
                    .runeLiteFormattedMessage(stripColors(chatMessage))
                    .timestamp((int) (System.currentTimeMillis() / 1000))
                    .build());
        }

        if (ircConfig.sidepanel()) {
            IrcPanel.message(formatMessage(sender + ": " + message));
        }
    }

    private String stripColors(String message) {
        return message.replaceAll("\u0003([0-9]{1,2})?|\u0015", "");
    }

    private String formatMessage(String message) {
        return escapeHtml4(message)
                .replaceAll("[\u000F\u0003]([^0-9]|$)", "</font>$1")
                .replaceAll("\u000310(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"darkcyan\">$1</font>")
                .replaceAll("\u000311(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"cyan\">$1</font>")
                .replaceAll("\u000312(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"blue\">$1</font>")
                .replaceAll("\u000313(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"pink\">$1</font>")
                .replaceAll("\u000314(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"grey\">$1</font>")
                .replaceAll("\u000315(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"lightgrey`\">$1</font>")
                .replaceAll("\u00030?1(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"black\">$1</font>")
                .replaceAll("\u00030?2(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"darkblue\">$1</font>")
                .replaceAll("\u00030?3(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"green\">$1</font>")
                .replaceAll("\u00030?4(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"red\">$1</font>")
                .replaceAll("\u00030?5(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"brown\">$1</font>")
                .replaceAll("\u00030?6(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"purple\">$1</font>")
                .replaceAll("\u00030?7(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"orange\">$1</font>")
                .replaceAll("\u00030?8(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"yellow\">$1</font>")
                .replaceAll("\u00030?9(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"chartreuse\">$1</font>")
                .replaceAll("\u000300?(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"white\">$1</font>");
    }

    @Override
    public void privmsg(Map<String, String> tags, String message) {
        String displayName = tags.get("display-name");

        if (message.startsWith("\u0001")) {
            message = message.replaceAll("\u0001(ACTION)?", "");
            addChatMessage("* " + displayName, message);
        } else {
            addChatMessage(displayName, message);
        }
    }

    @Override
    public void notice(Map<String, String> tags, String message) {
        String displayName = "(notice) " + tags.get("display-name");
        addChatMessage(displayName, message);
    }

    @Override
    public void roomstate(Map<String, String> tags) {
        log.debug("Room state: {}", tags);
    }

    @Override
    public void usernotice(Map<String, String> tags, String message) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        String sysmsg = tags.get("system-msg");
        addChatMessage("[System]", sysmsg);
    }

    @Override
    public void nick(String previous_nick, String nick) {
        addChatMessage("* Nick change", previous_nick + " is now known as " + nick);
    }

    @Override
    public void whois(String message) {
        addChatMessage("*", message);
    }

    @Override
    public void names(String message) {
        addChatMessage("* Names", message);
    }

    @Override
    public void topic(String target, String topic) {
        addChatMessage("* Topic", "[" + target + "] " + topic);
    }

    public void raw(String message) {
        addChatMessage("*", message);
    }

    @Override
    public void kick(String target, String kicker, String kicked, String reason) {
        addChatMessage("! Kick", kicker + " kicked " + kicked + " (" + reason + ")");
    }

    @Override
    public void join(String user, String chan) {
        addChatMessage("-> Join", user + " joined " + chan);
    }

    @Override
    public void part(String user, String chan, String message) {
        addChatMessage("<- Part", user + " left " + chan + " (" + message + ")");
    }

    @Override
    public void quit(String user, String message) {
        addChatMessage("<- Quit", user + " quit (" + message + ")");
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent) {
        if (!"chatDefaultReturn".equals(scriptCallbackEvent.getEventName())) {
            return;
        }

        final int[] intStack = client.getIntStack();
        int intStackCount = client.getIntStackSize();

        String message = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);

        String prefix = ircConfig.prefix();

        if (message.startsWith(prefix + prefix)) {
            message = message.substring(2);
            if (message.isEmpty() || IRCClient == null) {
                return;
            }

            intStack[intStackCount - 3] = 1;

            try {
                if (message.length() > 3) {
                    String trimmed = message.substring(3);

                    if (message.startsWith(("ns "))) {
                        IRCClient.nickserv(trimmed);
                    } else if (message.startsWith("cs ")) {
                        IRCClient.chanserv(trimmed);
                    } else if (message.startsWith("bs ")) {
                        IRCClient.botserv(trimmed);
                    } else if (message.startsWith("hs ")) {
                        IRCClient.hostserv(trimmed);
                    } else if (message.startsWith("ms ")) {
                        IRCClient.memoserv(trimmed);
                    } else if (message.startsWith("notice ")) {
                        String[] split = message.split(" ", 3);

                        if (split.length > 2) {
                            IRCClient.notice(message);
                            addChatMessage("(notice) -> " + split[1], split[2]);
                        }
                    } else if (message.startsWith("msg ")) {
                        String msg = trimmed.substring(1);

                        String[] split = msg.split(" ", 2);
                        if (split.length > 1) {
                            IRCClient.privateMsg(trimmed.substring(1));
                            addChatMessage("(pm) -> " + split[0], split[1]);
                        }
                    } else if (message.startsWith("me ")) {
                        IRCClient.actionMsg(trimmed);
                        addChatMessage("* " + ircConfig.username(), trimmed);
                    } else if (message.startsWith("mode ")) {
                        IRCClient.mode(trimmed.substring(2));
                    } else if (message.startsWith("umode ")) {
                        IRCClient.umode(trimmed.substring(3));
                    } else if (message.startsWith("topic")) {
                        String channel = trimmed.substring(2);

                        if ((channel.isEmpty()) || (channel.equals(" "))) {
                            channel = ircConfig.channel();
                        }

                        IRCClient.topic(channel);
                    } else if (message.startsWith("nick ")) {
                        IRCClient.nick(trimmed.substring(2));
                    } else if (message.startsWith("kick ")) {
                        String[] split = message.split(" ", 3);
                        String target;
                        String reason;

                        if (split.length > 1) {
                            target = split[1];
                        } else {
                            return;
                        }

                        if (split.length > 2) {
                            reason = split[2];
                        } else {
                            reason = "";
                        }

                        IRCClient.kick(target, reason);
                    } else if (message.startsWith("clear")) {
                        IrcPanel.clearLogs();
                    } else if (message.startsWith("whois ")) {
                        String[] split = message.split(" ", 2);

                        IRCClient.whois(split[1]);
                    } else if (message.startsWith("names")) {
                        IRCClient.names();
                    }
                }
            } catch (IOException e) {
                log.warn("failed to send message", e);
            }

            return;
        } else if (message.startsWith(ircConfig.prefix() + "")) {
            message = message.substring(1);
            if (message.isEmpty() || IRCClient == null) {
                return;
            }

            intStack[intStackCount - 3] = 1;

            try {
                IRCClient.privmsg(message);
                addChatMessage(IRCClient.getUsername(), message);
            } catch (IOException e) {
                log.warn("failed to send message", e);
            }
        }
    }

    private void startIrcPanel() {
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        panel = injector.getInstance(IrcPanel.class);
        panel.init();
        uiButton = NavigationButton.builder()
                .tooltip("SwiftIRC")
                .icon(icon)
                .priority(10)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(uiButton);
    }

    private void stopIrcPanel() {
        if (panel.isValid()) {
            panel.removeAll();

            clientToolbar.removeNavigation(uiButton);
        }
    }
}