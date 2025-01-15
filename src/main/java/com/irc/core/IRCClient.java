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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.google.common.base.Strings;

@Slf4j
public class IRCClient extends Thread implements AutoCloseable
{
	private static final String HOST = "irc.swiftirc.net";
	private static final String ident = "runelite";
	private static final int PORT = 6697;
	private static final int READ_TIMEOUT = 120000; // ms
	private static final int PING_TIMEOUT = 60000; // ms

	private final IrcListener ircListener;

	@Getter
    private String username;
	private final String channel;
	private final String password;
	private final String prefix;

	private Socket socket;
	private BufferedReader in;
	private Writer out;
	private long last;
	private boolean pingSent;

	public IRCClient(IrcListener ircListener, String username, String channel, String password, String prefix)
	{
		setName("IRC");
		this.ircListener = ircListener;
		this.username = username;
		this.channel = channel;
		this.password = password;
		this.prefix = prefix;
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

				if((line.startsWith(":fiery.ca.us.SwiftIRC.net 001") || line.startsWith(":tardis.en.uk.SwiftIRC.net 001"))
						&& line.contains("Welcome to the SwiftIRC IRC Network"))
				{
					if (!Strings.isNullOrEmpty(this.password))
					{
						nickservID();

						sleep(500);
					}

					join(channel);
				}
				else if (line.split(" ")[1].equals("433"))
				{
					ircListener.notice(Message.parse(line).getTags(), "Nick already in use. Please choose a new one.");
				}

				last = System.currentTimeMillis();
				pingSent = false;

				Message message = Message.parse(line);

				String[] args = message.getArguments();
				String arg_string = String.join(" ", args);

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
					case "NICK":
						String nick = message.getArguments()[0];

						ircListener.nick(message.getTags(), nick);
						break;
					// Whois
					case "307":
					case "308":
					case "309":
					case "310":
					case "311":
					case "312":
					case "313":
					case "314":
					case "315":
					case "316":
//					case "317": 182 1734738173 :seconds idle, signon time
//					case "318": :End of /WHOIS list.
					case "319":
					case "320":
					case "321":
					case "322":
					case "323":
					case "324":
					case "325":
					case "326":
					case "327":
					case "328":
					case "329":
//					case "330": <nick> :is logged in as
					case "671":
						ircListener.whois(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
						break;
					// Results of /names
					case "353":
						ircListener.names(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
						break;
				}
			}
		}
		catch (IOException | InterruptedException ex)
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
		nickserv("identify " + this.password);
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

	public void memoserv(String message) throws IOException
	{
		send("PRIVMSG", "MemoServ", message);
	}

	public void nick(String nick) throws IOException
	{
		send("NICK", nick);
		this.username = nick;
	}

	public void topic(String channel) throws IOException
	{
		send("TOPIC", channel);
	}

	public void umode(String modes) throws IOException
	{
		send("umode2", modes);
	}

	public void mode(String modes) throws IOException
	{
		send("mode", modes);
	}

	public void whois(String name) throws IOException
	{
		send("whois", name);
	}

	public void names() throws IOException
	{
		send("NAMES", channel);
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
