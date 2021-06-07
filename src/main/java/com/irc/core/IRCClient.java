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
package com.irc.core;

import lombok.extern.slf4j.Slf4j;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import com.google.common.base.Strings;

@Slf4j
public class IRCClient extends Thread implements AutoCloseable
{
	private static final String HOST = "fiery.swiftirc.net";
	private static final String ident = "runelite";
	private static final int PORT = 6697;
	private static final int READ_TIMEOUT = 120000; // ms
	private static final int PING_TIMEOUT = 60000; // ms

	private final IrcListener ircListener;

	private final String username;
	private final String channel;
	private final String password;
	private final String delimiter;

	private Socket socket;
	private BufferedReader in;
	private Writer out;
	private long last;
	private boolean pingSent;

	public IRCClient(IrcListener ircListener, String username, String channel, String password, String delimiter)
	{
		setName("IRC");
		this.ircListener = ircListener;
		this.username = username;
		this.channel = channel;
		this.password = password;
		this.delimiter = delimiter;
	}

	@Override
	public void close()
	{
		try
		{
			if (socket != null)
			{
				socket.close();
			}
		}
		catch (IOException ex)
		{
			log.warn("error closing socket", ex);
		}

		in = null;
		out = null;
	}

	@Override
	public void run()
	{
		try
		{
			SocketFactory socketFactory = SSLSocketFactory.getDefault();
			socket = socketFactory.createSocket(HOST, PORT);
			socket.setSoTimeout(READ_TIMEOUT);

			in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
		}
		catch (IOException ex)
		{
			log.warn("unable to setup irc client", ex);
			return;
		}

		try
		{
			register(username);

			String line;

			while ((line = read()) != null)
			{
				log.debug("<- {}", line);
				if(line.startsWith(":Global!services@services.host NOTICE")
						&& line.contains("We will now perform a passive scan on your IP to check for insecure proxies."))
				{
					if (!Strings.isNullOrEmpty(this.password))
					{
						nickservID();
					}

					join(channel);
				}

				last = System.currentTimeMillis();
				pingSent = false;

				Message message = Message.parse(line);
				// TODO: Support color codes https://github.com/ryanwohara/irc-plugin/issues/1

				switch (message.getCommand())
				{
					case "PING":
						send("PONG", message.getArguments()[0]);
						break;
					case "PRIVMSG":
						ircListener.privmsg(message.getTags(),
							message.getArguments()[1]);
						break;
					case "NOTICE":
						ircListener.notice(message.getTags(),
								message.getArguments()[1]);
						break;
					case "ROOMSTATE":
						ircListener.roomstate(message.getTags());
						break;
					case "USERNOTICE":
						ircListener.usernotice(message.getTags(),
							message.getArguments().length > 0 ? message.getArguments()[0] : null);
						break;
				}
			}
		}
		catch (IOException ex)
		{
			log.debug("error in irc client", ex);
		}
		finally
		{
			try
			{
				socket.close();
			}
			catch (IOException e)
			{
				log.warn(null, e);
			}
		}
	}

	public boolean isConnected()
	{
		return socket != null && socket.isConnected() && !socket.isClosed();
	}

	public void pingCheck()
	{
		if (out == null)
		{
			// client is not connected yet
			return;
		}

		if (!pingSent && System.currentTimeMillis() - last >= PING_TIMEOUT)
		{
			try
			{
				ping("swiftirc");
				pingSent = true;
			}
			catch (IOException e)
			{
				log.debug("Ping failure, disconnecting.", e);
				close();
			}
		}
		else if (pingSent)
		{
			log.debug("Ping timeout, disconnecting.");
			close();
		}
	}

	private void register(String username) throws IOException
	{
		send("NICK", username);
		send("USER", this.ident, "3", "*", username);
	}

	private void nickservID() throws IOException
	{
		nickserv("id " + this.password);
	}

	private void join(String channel) throws IOException
	{
		send("JOIN", channel);
	}

	private void ping(String destination) throws IOException
	{
		send("PING", destination);
	}

	public void privmsg(String message) throws IOException
	{
		send("PRIVMSG", channel, message);
	}

	public void actionMsg(String message) throws IOException
	{
		send("PRIVMSG", channel, "\u0001ACTION " + message + "\u0001");
	}

	public void notice(String message) throws IOException
	{
		send(message);
	}

	public void privateMsg(String message) throws IOException
	{
		String[] split = message.split(" ", 2);

		send("PRIVMSG", split[0], split[1]);
	}

	public void nickserv(String message) throws IOException
	{
		send("PRIVMSG", "NickServ", message);
	}

	public void chanserv(String message) throws IOException
	{
		send("PRIVMSG", "ChanServ", message);
	}

	public void botserv(String message) throws IOException
	{
		send("PRIVMSG", "BotServ", message);
	}

	public void hostserv(String message) throws IOException
	{
		send("PRIVMSG", "HostServ", message);
	}

	private void send(String command, String... args) throws IOException
	{
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append(command);
		for (int i = 0; i < args.length; ++i)
		{
			stringBuilder.append(' ');
			if (i + 1 == args.length)
			{
				stringBuilder.append(':');
			}
			stringBuilder.append(args[i]);
		}

		log.debug("-> {}", stringBuilder.toString());

		stringBuilder.append("\r\n");

		out.write(stringBuilder.toString());
		out.flush();
	}

	private String read() throws IOException
	{
		String line = in.readLine();
		if (line == null)
		{
			return null;
		}
		int len = line.length();
		while (len > 0 && (line.charAt(len - 1) == '\r' || line.charAt(len - 1) == '\n'))
		{
			--len;
		}

		return line.substring(0, len);
	}
}
