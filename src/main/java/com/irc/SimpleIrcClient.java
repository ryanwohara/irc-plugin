package com.irc;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SimpleIrcClient {
    private static final Pattern MESSAGE_PATTERN =
            Pattern.compile("^(?:[:@]([^\\s]+) )?([^\\s]+)(?: ((?:[^:\\s][^\\s]* ?)*))?(?: ?:(.*))?$");
    private static final Pattern USER_PREFIXES = Pattern.compile("^[~&@%+].+");
    private static final Pattern NUMERIC = Pattern.compile("^[0-9]+$");

    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<IrcEventListener> listeners = new CopyOnWriteArrayList<>();

    @Getter
    private String nick;
    private String username;
    private String realName;
    private String password;
    @Getter
    private final Set<String> channels = new HashSet<>();
    private final Map<String, Set<String>> channelUsers = new HashMap<>();

    private String host;
    private int port;
    private boolean secure;
    private boolean connected = false;
    private volatile boolean shuttingDown = false;

    public SimpleIrcClient server(String host, int port, boolean secure) {
        this.host = host;
        this.port = port;
        this.secure = secure;
        return this;
    }

    public SimpleIrcClient credentials(String nick, String username, String realName) {
        this.nick = nick;
        this.username = username;
        this.realName = realName;
        return this;
    }

    public void password(String password) {
        this.password = password;
    }

    public void connect() {
        shuttingDown = false;
        executor.submit(() -> {
            try {
                if (secure) {
                    createSecureConnection();
                } else {
                    socket = new Socket(host, port);
                }

                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                // Register connection
                if (password != null && !password.isEmpty()) {
                    sendRawLine("PASS " + password);
                }
                sendRawLine("NICK " + nick);
                sendRawLine("USER " + username + " 0 * :" + realName);

                connected = true;
                fireEvent(new IrcEvent(IrcEvent.Type.CONNECT, null, null, null, null));

                String line;
                try {
                    while (!shuttingDown && (line = reader.readLine()) != null) {
                        processLine(line);
                    }
                } catch (IOException e) {
                    if (!shuttingDown) {
                        log.error("Error reading from IRC server", e);
                        fireEvent(new IrcEvent(IrcEvent.Type.ERROR, null, null, null, e.getMessage()));
                    }
                }
            } catch (Exception e) {
                if (!shuttingDown) {
                    log.error("Error in IRC connection", e);
                    fireEvent(new IrcEvent(IrcEvent.Type.ERROR, null, null, null, e.getMessage()));
                }
            } finally {
                if (!shuttingDown) {
                    disconnect();
                }
            }
        });
    }

    private void createSecureConnection() throws IOException {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(host, port);

            sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());

            sslSocket.startHandshake();
            socket = sslSocket;
        } catch (Exception e) {
            log.error("SSL/TLS certificate validation failed - connection rejected for security", e);
        }
    }

    public void disconnect() {
        disconnect("");
    }

    public void disconnect(String reason) {
        if (connected) {
            try {
                if (reason.isEmpty()) {
                    reason = "Disconnecting";
                }

                shuttingDown = true;

                if (writer != null) {
                    try {
                        sendRawLine(reason);
                        writer.close();
                    } catch (IOException ignored) {}
                }

                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {}
                }

                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            } finally {
                connected = false;
                fireEvent(new IrcEvent(IrcEvent.Type.DISCONNECT, null, null, null, null));
            }
        }
    }

    public void joinChannel(String channel, String password) {
        if (connected) {
            String command = "JOIN " + channel;
            if (password != null && !password.isEmpty()) {
                command += " " + password;
            }
            sendRawLine(command);
            channels.add(channel);
        }
    }

    public void leaveChannel(String channel) {
        leaveChannel(channel, null);
    }

    public void leaveChannel(String channel, String reason) {
        if (connected && channels.contains(channel)) {
            String command = "PART " + channel;
            if (reason != null && !reason.isEmpty()) {
                command += " :" + reason;
            }
            sendRawLine(command);
            channels.remove(channel);
            channelUsers.remove(channel);
        }
    }

    public void sendMessage(String target, String message) {
        if (connected) {
            sendRawLine("PRIVMSG " + target + " :" + message);
        }
    }

    public void sendAction(String target, String action) {
        if (connected) {
            sendRawLine("PRIVMSG " + target + " :\u0001ACTION " + action + "\u0001");
        }
    }

    public void sendNotice(String target, String message) {
        if (connected) {
            sendRawLine("NOTICE " + target + " :" + message);
        }
    }

    public void setNick(String newNick) {
        if (connected) {
            sendRawLine("NICK " + newNick);
            this.nick = newNick;
        }
    }

    public synchronized void sendRawLine(String line) {
        try {
            if (writer != null) {
                writer.write(line + "\r\n");
                writer.flush();
            }
        } catch (IOException e) {
            log.error("Error sending IRC message", e);
        }
    }

    private void processLine(String line) {
        Matcher matcher = MESSAGE_PATTERN.matcher(line);

        if (matcher.matches()) {
            String sourceRaw = matcher.group(1);
            String command = matcher.group(2);
            String paramsRaw = matcher.group(3);
            String trailing = matcher.group(4);

            String source = sourceRaw != null ? sourceRaw : "";
            List<String> params = new ArrayList<>();

            if (paramsRaw != null) {
                for (String param : paramsRaw.split(" ")) {
                    if (!param.isEmpty()) {
                        params.add(param);
                    }
                }
            }

            if (trailing != null) {
                params.add(trailing);
            }

            // Handle ping to keep connection alive
            if (command.equals("PING")) {
                sendRawLine("PONG " + (params.isEmpty() ? "" : params.get(0)));
                return;
            }

            // Parse command for events
            processCommand(source, command, params);
        }
    }

    private void processCommand(String source, String command, List<String> params) {
        String sourceNick = extractNick(source);

        switch (command.toUpperCase()) {
            case "PRIVMSG":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);

                    // Handle CTCPs
                    if (message.startsWith("\u0001") && message.endsWith("\u0001")) {
                        handleCtcp(source, target, message);
                    } else {
                        // Regular message - channel or private
                        String messageChannel = target.startsWith("#") ? target : sourceNick;
                        fireEvent(new IrcEvent(IrcEvent.Type.MESSAGE, sourceNick, messageChannel, message, null));
                    }
                }
                break;

            case "JOIN":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    fireEvent(new IrcEvent(IrcEvent.Type.JOIN, sourceNick, channel, null, null));

                    // Add to channel users
                    channelUsers.computeIfAbsent(channel, k -> new HashSet<>()).add(sourceNick);
                }
                break;

            case "PART":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    String reason = params.size() > 1 ? params.get(1) : "";

                    if (!sourceNick.equals(nick)) {
                        fireEvent(new IrcEvent(IrcEvent.Type.PART, sourceNick, channel, reason, null));
                    }

                    // Remove from channel users
                    if (channelUsers.containsKey(channel)) {
                        channelUsers.get(channel).remove(sourceNick);
                    }
                }
                break;

            case "QUIT":
                String quitMessage = params.isEmpty() ? "" : params.get(0);

                List<String> userChannels = new ArrayList<>();
                for (Map.Entry<String, Set<String>> entry : channelUsers.entrySet()) {
                    if (entry.getValue().contains(sourceNick)) {
                        userChannels.add(entry.getKey());
                    }
                }

                fireEvent(new IrcEvent(
                        IrcEvent.Type.QUIT,
                        sourceNick,
                        null,
                        quitMessage,
                        String.join(",", userChannels)
                ));

                // Remove from all channels
                for (Set<String> users : channelUsers.values()) {
                    users.remove(sourceNick);
                }
                break;

            case "NICK":
                if (!params.isEmpty()) {
                    String newNick = params.get(0);

                    userChannels = new ArrayList<>();
                    for (Map.Entry<String, Set<String>> entry : channelUsers.entrySet()) {
                        if (entry.getValue().contains(sourceNick)) {
                            userChannels.add(entry.getKey());
                        }
                    }

                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_CHANGE, sourceNick, null, newNick, String.join(",", userChannels)));

                    // Update nickname in channel users
                    for (Set<String> users : channelUsers.values()) {
                        if (users.remove(sourceNick)) {
                            users.add(newNick);
                        }
                    }
                }
                break;

            case "KICK":
                if (params.size() >= 2) {
                    String channel = params.get(0);
                    String kickedUser = params.get(1);
                    String kickMessage = params.size() > 2 ? params.get(2) : "";

                    fireEvent(new IrcEvent(IrcEvent.Type.KICK, sourceNick, channel,
                            kickedUser + " " + kickMessage, null));

                    // Remove from channel users
                    if (channelUsers.containsKey(channel)) {
                        channelUsers.get(channel).remove(kickedUser);
                    }
                }
                break;

            case "NOTICE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);

                    // Determine if this is a server notice or a user notice
                    if (source.contains("!")) {
                        // User notice
                        fireEvent(new IrcEvent(IrcEvent.Type.NOTICE, sourceNick, target, message, null));
                    } else {
                        // Server notice
                        fireEvent(new IrcEvent(IrcEvent.Type.SERVER_NOTICE, source, null, message, null));
                    }
                }
                break;

            case "MODE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    StringBuilder modeString = new StringBuilder();
                    for (String param : params) {
                        if (params.indexOf(param) > 0) {
                            modeString.append(" ").append(param);
                        }
                    }

                    if (target.startsWith("#")) {
                        // Channel mode
                        fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* " + sourceNick + " sets mode(s)", target, modeString.toString(), null));
                    } else {
                        // User mode
                        fireEvent(new IrcEvent(IrcEvent.Type.USER_MODE, sourceNick, target, modeString.toString(), null));
                    }
                }
                break;

            case "TOPIC":
                if (params.size() >= 2) {
                    String channel = params.get(0);
                    String topic = params.get(1);
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC, sourceNick, channel, topic, null));
                }
                break;

            // Numeric replies
            default:
                Matcher matcher = NUMERIC.matcher(command);
                if (matcher.matches()) {
                    int numeric = Integer.parseInt(command);
                    handleNumeric(numeric, params);
                }
                break;
        }
    }

    private void handleCtcp(String source, String target, String message) {
        String ctcp = message.substring(1, message.length() - 1);
        String[] parts = ctcp.split(" ", 2);
        String command = parts[0].toUpperCase();
        String param = parts.length > 1 ? parts[1] : "";
        String sourceNick = extractNick(source);

        switch (command) {
            case "ACTION":
                // This is a /me command
                String actionChannel = target.startsWith("#") ? target : sourceNick;
                fireEvent(new IrcEvent(IrcEvent.Type.ACTION, sourceNick, actionChannel, param, null));
                break;

            case "VERSION":
                // Auto-respond to VERSION requests
                sendRawLine("NOTICE " + sourceNick + " :\u0001VERSION RuneLite IRC Plugin\u0001");
                break;

            case "PING":
                // Auto-respond to PING requests
                sendRawLine("NOTICE " + sourceNick + " :\u0001PING " + param + "\u0001");
                break;
        }
    }

    private void handleNumeric(int numeric, List<String> params) {
        switch (numeric) {
            case 1: // RPL_WELCOME - This means we're fully registered with the server
                connected = true; // Mark as truly connected and ready
                fireEvent(new IrcEvent(IrcEvent.Type.REGISTERED, null, null, null, null));
                break;

            case 324: // Channel modes
                if (params.size() >= 2) {
                    String target = params.get(1);
                    StringBuilder message = new StringBuilder();
                    for (String param : params) {
                        if (params.indexOf(param) > 1) {
                            message.append(" ").append(param);
                        }
                    }

                    fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* Modes", target, message.toString(), null));
                }
                break;

            case 332: // RPL_TOPIC - Topic message
                if (params.size() >= 3) {
                    String channel = params.get(1);
                    String topic = params.get(2);
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC, "System", channel, topic, null));
                }
                break;

            case 333: // RPL_TOPICWHOTIME - Topic setter info
                if (params.size() >= 4) {
                    String channel = params.get(1);
                    String setter = params.get(2);
                    // You might want to format this timestamp
                    long timestamp = Long.parseLong(params.get(3));
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC_INFO, "* Topic set by", channel,
                            setter, null));
                }
                break;

            case 353: // RPL_NAMREPLY - Channel user list
                if (params.size() >= 4) {
                    String channel = params.get(2);
                    String[] users = params.get(3).split(" ");

                    Set<String> channelUserSet = channelUsers.computeIfAbsent(channel, k -> new HashSet<>());
                    for (String user : users) {
                        if (!user.isEmpty()) {
                            // Strip mode prefixes (@, +, etc.)
                            Matcher matcher = USER_PREFIXES.matcher(user);
                            if (matcher.matches()) {
                                user = user.substring(1);
                            }
                            channelUserSet.add(user);
                        }
                    }

                    fireEvent(new IrcEvent(IrcEvent.Type.NAMES, null, channel, String.join(" ", users), null));
                }
                break;

            case 433: // ERR_NICKNAMEINUSE
                if (params.size() >= 2) {
                    String takenNick = params.get(1);
                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_IN_USE, null, null, takenNick, null));

                    // Auto-append underscore to nick
                    setNick(takenNick + "_");
                    nick += "_";
                }
                break;
        }
    }

    private String extractNick(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        int exclamation = source.indexOf('!');
        return exclamation > 0 ? source.substring(0, exclamation) : source;
    }

    public void addEventListener(IrcEventListener listener) {
        listeners.add(listener);
    }

    public void removeEventListener(IrcEventListener listener) {
        listeners.remove(listener);
    }

    private final List<Runnable> pendingCommands = new CopyOnWriteArrayList<>();

    /**
     * Queue a command to be executed once fully registered with the server
     */
    public void executeWhenRegistered(Runnable command) {
        if (connected) {
            command.run();
        } else {
            pendingCommands.add(command);
        }
    }

    private void fireEvent(IrcEvent event) {
        // If we just got registered, execute any pending commands
        if (event.getType() == IrcEvent.Type.REGISTERED) {
            // Execute all pending commands that were waiting for registration
            for (Runnable command : pendingCommands) {
                command.run();
            }
            pendingCommands.clear();
        }

        for (IrcEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    // Event handling interface
    public interface IrcEventListener {
        void onEvent(IrcEvent event);
    }

    // Event class for all IRC events
    @Getter
    public static class IrcEvent {
        public enum Type {
            CONNECT, DISCONNECT, REGISTERED, MESSAGE, ACTION, JOIN, PART, QUIT,
            NICK_CHANGE, KICK, NOTICE, SERVER_NOTICE, CHANNEL_MODE, USER_MODE,
            TOPIC, NAMES, NICK_IN_USE, ERROR, TOPIC_INFO
        }

        private final Type type;
        private final String source;
        private final String target;
        private final String message;
        private final String additionalData;

        public IrcEvent(Type type, String source, String target, String message, String additionalData) {
            this.type = type;
            this.source = source;
            this.target = target;
            this.message = message;
            this.additionalData = additionalData;
        }
    }
}