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
import java.util.Base64;
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
            Pattern.compile("^(?:[:@](\\S+) )?(\\S+)(?: ((?:[^:\\s]\\S* ?)*))?(?: ?:(.*))?$");
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
    private String saslAccount;
    private String saslPassword;
    private boolean saslEnabled = false;
    @Getter
    private final Set<String> channels = new HashSet<>();
    private final Map<String, Set<String>> channelUsers = new HashMap<>();

    private String host;
    private int port;
    private boolean secure;
    private boolean connected = false;
    private volatile boolean shuttingDown = false;

    String currentTagTime;   // package-private: accessed by TestableIrcClient subclass
    String currentTagBatch;  // package-private: accessed by TestableIrcClient subclass

    private final Map<String, List<IrcEvent>> activeBatches = new HashMap<>();
    private final Map<String, String> activeBatchChannels = new HashMap<>();

    boolean capHistorySupported = false;  // package-private: accessed by TestableIrcClient subclass
    private boolean capEndSent = false;
    private final Set<String> advertisedCaps = new HashSet<>();

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

    public void sasl(String account, String password) {
        this.saslAccount = account;
        this.saslPassword = password;
        this.saslEnabled = password != null && !password.isEmpty();
    }

    static String saslPlainResponse(String authcid, String password) {
        String payload = "\0" + authcid + "\0" + password;
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
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

                advertisedCaps.clear();
                capEndSent = false;
                capHistorySupported = false;
                sendRawLine("NICK " + nick);
                sendRawLine("USER " + username + " 0 * :" + realName);
                sendRawLine("CAP LS 302");

                connected = true;
                fireEvent(new IrcEvent(IrcEvent.Type.CONNECT, null, null, null, null));

                String line;
                try {
                    while (!shuttingDown && (line = reader.readLine()) != null) {
                        processLine(line);
                    }

                    if (!shuttingDown) disconnect();
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
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) factory.createSocket(host, port);
        sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());
        sslSocket.startHandshake();
        sslSocket.setSoTimeout(240000);
        socket = sslSocket;
    }

    public void disconnect() {
        disconnect("");
    }

    public void disconnect(String reason) {
        if (shuttingDown || !connected) return;

        shuttingDown = true;
        try {
            if (writer != null) {
                try {
                    sendRawLine("QUIT :" + (reason.isEmpty() ? "Disconnecting" : reason));
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                }
            }
            if (reader != null) try {
                reader.close();
            } catch (IOException ignored) {
            }
            if (socket != null) try {
                socket.close();
            } catch (IOException ignored) {
            }
        } finally {
            activeBatches.clear();
            activeBatchChannels.clear();
            connected = false;
            fireEvent(new IrcEvent(IrcEvent.Type.DISCONNECT, null, null, null, null));
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
        }
    }

    public synchronized void sendRawLine(String line) {
        if (writer == null) return;
        try {
            writer.write(line + "\r\n");
            writer.flush();
        } catch (IOException e) {
            log.error("Error sending IRC message", e);
        }
    }

    void processLine(String line) {
        // Reset per-line tag state
        currentTagTime = null;
        currentTagBatch = null;

        // Strip and parse IRCv3 message tags (@key=value;...)
        if (line.startsWith("@")) {
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx > 0) {
                String tagSegment = line.substring(1, spaceIdx);
                line = line.substring(spaceIdx + 1);
                for (String tag : tagSegment.split(";")) {
                    int eq = tag.indexOf('=');
                    if (eq > 0) {
                        String key = tag.substring(0, eq);
                        String value = tag.substring(eq + 1);
                        if ("time".equals(key)) currentTagTime = value;
                        else if ("batch".equals(key)) currentTagBatch = value;
                    }
                }
            }
        }

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

            if (command.equals("PING")) {
                sendRawLine("PONG " + (params.isEmpty() ? "" : params.get(0)));
                return;
            }

            processCommand(source, command, params);
        }
    }

    private void processCommand(String source, String command, List<String> params) {
        String sourceNick = extractNick(source);

        // IRCv3 batch intercept: accumulate tagged messages instead of processing normally
        if (currentTagBatch != null && activeBatches.containsKey(currentTagBatch)) {
            String batchRef = currentTagBatch;
            if (command.equalsIgnoreCase("PRIVMSG") && params.size() >= 2) {
                String target = params.get(0);
                String msgBody = params.get(1);
                if (msgBody.startsWith("\u0001") && msgBody.endsWith("\u0001")) {
                    // CTCP — check for ACTION
                    String ctcp = msgBody.substring(1, msgBody.length() - 1);
                    String[] parts = ctcp.split(" ", 2);
                    if ("ACTION".equals(parts[0])) {
                        String actionText = parts.length > 1 ? parts[1] : "";
                        activeBatches.get(batchRef).add(
                            new IrcEvent(IrcEvent.Type.ACTION, sourceNick, target, actionText, currentTagTime)
                        );
                    }
                } else {
                    activeBatches.get(batchRef).add(
                        new IrcEvent(IrcEvent.Type.MESSAGE, sourceNick, target, msgBody, currentTagTime)
                    );
                }
                return;
            }
            // Non-PRIVMSG commands in a chathistory batch are silently ignored
            return;
        }

        switch (command.toUpperCase()) {
            case "PRIVMSG":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);

                    if (message.startsWith("\u0001") && message.endsWith("\u0001")) {
                        handleCtcp(source, target, message);
                    } else {
                        String messageChannel = target.startsWith("#") ? target : sourceNick;
                        fireEvent(new IrcEvent(IrcEvent.Type.MESSAGE, sourceNick, messageChannel, message, null));
                    }
                }
                break;

            case "JOIN":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    fireEvent(new IrcEvent(IrcEvent.Type.JOIN, sourceNick, channel, null, null));
                    channelUsers.computeIfAbsent(channel, k -> new HashSet<>()).add(sourceNick);
                    if (sourceNick.equals(nick) && capHistorySupported) {
                        sendRawLine("CHATHISTORY LATEST " + channel + " * 100");
                    }
                }
                break;

            case "PART":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    String reason = params.size() > 1 ? params.get(1) : "";

                    if (!sourceNick.equals(nick)) {
                        fireEvent(new IrcEvent(IrcEvent.Type.PART, sourceNick, channel, reason, null));
                        if (channelUsers.containsKey(channel)) {
                            channelUsers.get(channel).remove(sourceNick);
                        }
                    } else {
                        channelUsers.remove(channel);
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
                fireEvent(new IrcEvent(IrcEvent.Type.QUIT, sourceNick, null, quitMessage, String.join(",", userChannels)));
                for (Set<String> users : channelUsers.values()) {
                    users.remove(sourceNick);
                }
                break;

            case "NICK":
                if (!params.isEmpty()) {
                    String newNick = params.get(0);
                    if (sourceNick.equals(this.nick)) {
                        this.nick = newNick;
                    }

                    userChannels = new ArrayList<>();
                    for (Map.Entry<String, Set<String>> entry : channelUsers.entrySet()) {
                        if (entry.getValue().contains(sourceNick)) {
                            userChannels.add(entry.getKey());
                        }
                    }
                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_CHANGE, sourceNick, null, newNick, String.join(",", userChannels)));
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

                    fireEvent(new IrcEvent(IrcEvent.Type.KICK, sourceNick, channel, kickedUser + " " + kickMessage, null));
                    if (!kickedUser.equals(nick)) {
                        if (channelUsers.containsKey(channel)) {
                            channelUsers.get(channel).remove(kickedUser);
                        }
                    } else {
                        channelUsers.remove(channel);
                    }
                }
                break;

            case "NOTICE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);
                    if (source.contains("!")) {
                        fireEvent(new IrcEvent(IrcEvent.Type.NOTICE, sourceNick, target, message, null));
                    } else {
                        fireEvent(new IrcEvent(IrcEvent.Type.SERVER_NOTICE, source, null, message, null));
                    }
                }
                break;

            case "MODE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    StringBuilder modeString = new StringBuilder();
                    for (int i = 1; i < params.size(); i++) {
                        modeString.append(" ").append(params.get(i));
                    }

                    if (target.startsWith("#")) {
                        fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* " + sourceNick + " sets mode(s)", target, modeString.toString().trim(), null));
                    } else {
                        fireEvent(new IrcEvent(IrcEvent.Type.USER_MODE, sourceNick, target, modeString.toString().trim(), null));
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

            case "BATCH":
                if (params.isEmpty()) break;
                String batchToken = params.get(0);
                if (batchToken.startsWith("+")) {
                    String ref = batchToken.substring(1);
                    // params = [+ref, type, channel]
                    String batchChannel = params.size() >= 3 ? params.get(2) : "";
                    activeBatches.put(ref, new ArrayList<>());
                    activeBatchChannels.put(ref, batchChannel);
                } else if (batchToken.startsWith("-")) {
                    String ref = batchToken.substring(1);
                    List<IrcEvent> accumulated = activeBatches.remove(ref);
                    String batchChannel = activeBatchChannels.remove(ref);
                    if (accumulated != null && batchChannel != null) {
                        fireEvent(new IrcEvent(IrcEvent.Type.HISTORY_BATCH, null, batchChannel, null, null, accumulated));
                    }
                }
                break;

            case "CAP":
                if (params.size() < 2) break;
                String capSubCommand = params.get(1).toUpperCase();
                switch (capSubCommand) {
                    case "LS": {
                        // Check for multi-line continuation: params = [clientNick, LS, *, cap-list] vs [clientNick, LS, cap-list]
                        boolean isContinuation = params.size() >= 4 && "*".equals(params.get(2));
                        String capList = isContinuation ? params.get(3) : (params.size() >= 3 ? params.get(2) : "");
                        for (String cap : capList.split(" ")) {
                            if (!cap.isEmpty()) {
                                int eq = cap.indexOf('=');
                                advertisedCaps.add(eq > 0 ? cap.substring(0, eq) : cap);
                            }
                        }
                        if (!isContinuation) {
                            // Final LS line: decide what to request
                            List<String> toRequest = new ArrayList<>();
                            if (advertisedCaps.contains("chathistory")) toRequest.add("chathistory");
                            if (advertisedCaps.contains("batch")) toRequest.add("batch");
                            if (advertisedCaps.contains("server-time")) toRequest.add("server-time");
                            if (saslEnabled && advertisedCaps.contains("sasl")) toRequest.add("sasl");
                            if (!toRequest.isEmpty()) {
                                sendRawLine("CAP REQ :" + String.join(" ", toRequest));
                            } else if (!capEndSent) {
                                sendRawLine("CAP END");
                                capEndSent = true;
                            }
                        }
                        break;
                    }
                    case "ACK": {
                        String acked = params.size() >= 3 ? params.get(2) : "";
                        boolean saslAcked = false;
                        for (String cap : acked.split(" ")) {
                            String c = cap.trim();
                            if ("chathistory".equals(c)) capHistorySupported = true;
                            if ("sasl".equals(c)) saslAcked = true;
                        }
                        if (saslAcked) {
                            // Begin SASL PLAIN; hold CAP END until the SASL exchange completes
                            // (RPL_SASLSUCCESS or an error numeric).
                            sendRawLine("AUTHENTICATE PLAIN");
                        } else if (!capEndSent) {
                            sendRawLine("CAP END");
                            capEndSent = true;
                        }
                        break;
                    }
                    case "NAK":
                        capHistorySupported = false;
                        if (!capEndSent) {
                            sendRawLine("CAP END");
                            capEndSent = true;
                        }
                        break;
                }
                break;

            case "AUTHENTICATE":
                // Server replies "AUTHENTICATE +" when ready for the SASL PLAIN response.
                if (saslEnabled && !params.isEmpty() && "+".equals(params.get(0))) {
                    String authcid = (saslAccount != null && !saslAccount.isEmpty()) ? saslAccount : nick;
                    sendRawLine("AUTHENTICATE " + saslPlainResponse(authcid, saslPassword));
                }
                break;

            default:
                if (NUMERIC.matcher(command).matches()) {
                    int numeric = Integer.parseInt(command);
                    handleNumeric(numeric, source, params);
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
                String actionChannel = target.startsWith("#") ? target : sourceNick;
                fireEvent(new IrcEvent(IrcEvent.Type.ACTION, sourceNick, actionChannel, param, null));
                break;
            case "VERSION":
                sendRawLine("NOTICE " + sourceNick + " :\u0001VERSION RuneLite IRC Plugin\u0001");
                break;
            case "PING":
                sendRawLine("NOTICE " + sourceNick + " :\u0001PING " + param + "\u0001");
                break;
        }
    }

    private void handleNumeric(int numeric, String source, List<String> params) {
        switch (numeric) {
            case 1:
                // RPL_WELCOME: the server states our final, authoritative nick here.
                // This is how we learn our real nick after a 433/nick-in-use retry, as
                // the server does not echo a NICK during registration.
                if (!params.isEmpty()) {
                    nick = params.get(0);
                }
                connected = true;
                fireEvent(new IrcEvent(IrcEvent.Type.REGISTERED, null, null, null, null));
                break;
            case 301:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is away: %s", params.get(1), params.get(2)), null));
                break;
            case 311:
                if (params.size() >= 5)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is %s@%s (%s)", params.get(1), params.get(2), params.get(3), params.get(5)), null));
                break;
            case 312:
                if (params.size() >= 4)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is connected to %s (%s)", params.get(1), params.get(2), params.get(3)), null));
                break;
            case 313:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), params.get(1) + " is an IRC operator", null));
                break;
            case 317:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s has been idle for %s seconds", params.get(1), params.get(2)), null));
                break;
            case 318:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), "End of WHOIS for " + params.get(1), null));
                break;
            case 319:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is on channels: %s", params.get(1), params.get(2)), null));
                break;
            case 324:
                if (params.size() >= 2) {
                    String target = params.get(1);
                    StringBuilder message = new StringBuilder();
                    for (int i = 2; i < params.size(); i++) message.append(" ").append(params.get(i));
                    fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* Modes", target, message.toString().trim(), null));
                }
                break;
            case 332:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC, "System", params.get(1), params.get(2), null));
                break;
            case 333:
                if (params.size() >= 4)
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC_INFO, "* Topic set by", params.get(1), params.get(2), null));
                break;
            case 353:
                if (params.size() >= 4) {
                    String channel = params.get(2);
                    String[] users = params.get(3).split(" ");
                    Set<String> channelUserSet = channelUsers.computeIfAbsent(channel, k -> new HashSet<>());
                    for (String user : users) {
                        if (!user.isEmpty())
                            channelUserSet.add(USER_PREFIXES.matcher(user).matches() ? user.substring(1) : user);
                    }
                    fireEvent(new IrcEvent(IrcEvent.Type.NAMES, null, channel, String.join(" ", users), null));
                }
                break;
            case 433:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_IN_USE, null, null, params.get(1), null));
                break;
            case 475:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.BAD_CHANNEL_KEY, null, params.get(1), params.get(2), null));
                break;
            case 903: // RPL_SASLSUCCESS
                if (!capEndSent) {
                    sendRawLine("CAP END");
                    capEndSent = true;
                }
                fireEvent(new IrcEvent(IrcEvent.Type.SASL_SUCCESS, null, null,
                        params.size() >= 2 ? params.get(params.size() - 1) : "SASL authentication successful", null));
                break;
            case 902: // ERR_NICKLOCKED
            case 904: // ERR_SASLFAIL
            case 905: // ERR_SASLTOOLONG
            case 906: // ERR_SASLABORTED
            case 908: // RPL_SASLMECHS
                // Authentication failed; end capability negotiation so registration can proceed
                // unauthenticated rather than stalling.
                if (!capEndSent) {
                    sendRawLine("CAP END");
                    capEndSent = true;
                }
                fireEvent(new IrcEvent(IrcEvent.Type.SASL_FAILED, null, null,
                        params.size() >= 2 ? params.get(params.size() - 1) : "SASL authentication failed", null));
                break;
        }
    }

    private String extractNick(String source) {
        if (source == null || source.isEmpty()) return "";
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
        if (event.getType() == IrcEvent.Type.REGISTERED) {
            for (Runnable command : pendingCommands) {
                command.run();
            }
            pendingCommands.clear();
        }

        for (IrcEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    public interface IrcEventListener {
        void onEvent(IrcEvent event);
    }

    @Getter
    public static class IrcEvent {
        public enum Type {
            CONNECT, DISCONNECT, REGISTERED, MESSAGE, ACTION, JOIN, PART, QUIT,
            NICK_CHANGE, KICK, NOTICE, SERVER_NOTICE, CHANNEL_MODE, USER_MODE,
            TOPIC, NAMES, NICK_IN_USE, ERROR, TOPIC_INFO, BAD_CHANNEL_KEY, WHOIS_REPLY,
            HISTORY_BATCH, SASL_SUCCESS, SASL_FAILED
        }

        private final Type type;
        private final String source;
        private final String target;
        private final String message;
        private final String additionalData;
        private final List<IrcEvent> historyMessages;

        public IrcEvent(Type type, String source, String target, String message,
                        String additionalData, List<IrcEvent> historyMessages) {
            this.type = type;
            this.source = source;
            this.target = target;
            this.message = message;
            this.additionalData = additionalData;
            this.historyMessages = historyMessages;
        }

        public IrcEvent(Type type, String source, String target, String message, String additionalData) {
            this(type, source, target, message, additionalData, null);
        }
    }
}