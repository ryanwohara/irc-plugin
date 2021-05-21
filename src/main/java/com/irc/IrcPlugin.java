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

import com.google.common.base.Strings;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.chat.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatboxInput;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PrivateMessageInput;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.irc.core.IRCClient;
import com.irc.core.IrcListener;
import net.runelite.client.task.Schedule;

import javax.inject.Inject;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@PluginDescriptor(
		name = "IRC",
		description = "Integrates IRC with RS",
		enabledByDefault = false
)
@Slf4j
public class IrcPlugin extends Plugin implements IrcListener, ChatboxInputListener
{
	@Inject
	private IrcConfig ircConfig;

	@Inject
	private Client client;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private CommandManager commandManager;

	private IRCClient IRCClient;

	@Override
	protected void startUp()
	{
		connect();
		commandManager.register(this);
	}

	@Override
	protected void shutDown()
	{
		if (IRCClient != null)
		{
			IRCClient.close();
			IRCClient = null;
		}

		commandManager.unregister(this);
	}

	@Provides
	IrcConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(IrcConfig.class);
	}

	private synchronized void connect()
	{
		if (IRCClient != null)
		{
			log.debug("Terminating IRC client {}", IRCClient);
			IRCClient.close();
			IRCClient = null;
		}

		if (!Strings.isNullOrEmpty(ircConfig.username()))
		{
			String channel;

			if (Strings.isNullOrEmpty(ircConfig.channel()))
			{
				channel = "#rshelp";
			}
			else
			{
				channel = ircConfig.channel().toLowerCase();
				if (!channel.startsWith("#"))
				{
					channel = "#" + channel;
				}

				if (channel.contains(","))
				{
					channel = channel.split(",")[0];
				}

			}

			log.debug("Connecting to IRC as {}", ircConfig.username());

			IRCClient = new IRCClient(
					this,
					ircConfig.username(),
					channel,
					ircConfig.password(),
					ircConfig.delimiter()
			);
			IRCClient.start();
		}
	}

	@Schedule(period = 30, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void checkClient()
	{
		if (IRCClient != null)
		{
			if (IRCClient.isConnected())
			{
				IRCClient.pingCheck();
			}

			if (!IRCClient.isConnected())
			{
				log.debug("Reconnecting...");

				connect();
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (!configChanged.getGroup().equals("irc"))
		{
			return;
		}

		connect();
	}

	private void addChatMessage(String sender, String message)
	{
		String chatMessage = new ChatMessageBuilder()
				.append(ChatColorType.NORMAL)
				.append(message)
				.build();

		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.FRIENDSCHAT)
				.sender("IRC")
				.name(sender)
				.runeLiteFormattedMessage(chatMessage)
				.timestamp((int) (System.currentTimeMillis() / 1000))
				.build());
	}

	@Override
	public void privmsg(Map<String, String> tags, String message)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String displayName = tags.get("display-name");

		if (message.startsWith("\u0001"))
		{
			addChatMessage("* " + displayName, message.replaceAll("\u0001(ACTION)?", ""));
		}
		else
		{
			addChatMessage(displayName, message);
		}
	}

	@Override
	public void notice(Map<String, String> tags, String message)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String displayName = "(notice) " + tags.get("display-name");
		addChatMessage(displayName, message);
	}

	@Override
	public void roomstate(Map<String, String> tags)
	{
		log.debug("Room state: {}", tags);
	}

	@Override
	public void usernotice(Map<String, String> tags, String message)
	{
		log.debug("Usernotice tags: {} message: {}", tags, message);

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String sysmsg = tags.get("system-msg");
		addChatMessage("[System]", sysmsg);
	}

	@Override
	public boolean onChatboxInput(ChatboxInput chatboxInput)
	{
		String message = chatboxInput.getValue();
		log.warn(message);
		log.warn(ircConfig.delimiter() + ircConfig.delimiter());
		if (message.startsWith(ircConfig.delimiter() + ircConfig.delimiter()))
		{
			message = message.substring(2);
			if (message.isEmpty() || IRCClient == null)
			{
				return true;
			}

			try
			{
				String trimmed = message.substring(3);

				if (message.startsWith(("ns ")))
				{
					IRCClient.nickserv(trimmed);
				}
				else if (message.startsWith("cs "))
				{
					IRCClient.chanserv(trimmed);
				}
				else if (message.startsWith("bs "))
				{
					IRCClient.botserv(trimmed);
				}
				else if (message.startsWith("hs "))
				{
					IRCClient.hostserv(trimmed);
				}
				else if (message.startsWith("notice "))
				{
					IRCClient.notice(message);
					addChatMessage("-> ", message);
				}
				else if (message.startsWith("msg "))
				{
					IRCClient.privateMsg(trimmed.substring(1));
					addChatMessage(ircConfig.username(), trimmed.substring(1));
				}
				else if (message.startsWith("me "))
				{
					IRCClient.actionMsg(trimmed);
					addChatMessage("* " + ircConfig.username(), trimmed);
				}
				else if (message.startsWith("mode "))
				{
					// TODO mode support
				}
				else if (message.startsWith("umode "))
				{
					// TODO user mode support
				}
				else if (message.startsWith("topic "))
				{
					// TODO topic support - strip color codes
				}
			}
			catch (IOException e)
			{
				log.warn("failed to send message", e);
			}

			return true;
		}
		else if (message.startsWith(ircConfig.delimiter()))
		{
			message = message.substring(1);
			if (message.isEmpty() || IRCClient == null)
			{
				return true;
			}

			try
			{
				IRCClient.privmsg(message);
				addChatMessage(ircConfig.username(), message);
			}
			catch (IOException e)
			{
				log.warn("failed to send message", e);
			}

			return true;
		}
		return false;
	}

	@Override
	public boolean onPrivateMessageInput(PrivateMessageInput privateMessageInput)
	{
		return false;
	}
}