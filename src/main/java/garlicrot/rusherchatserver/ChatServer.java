package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.*;

public class ChatServer extends WebSocketServer {
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());
    private static final int PORT = 42424;
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final long MIN_INTERVAL_MS = 1000;

    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();
    private final Map<WebSocket, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> lastWhisperFrom = new ConcurrentHashMap<>();

    static {
        Logger logger = Logger.getLogger("garlicrot.rusherchatserver");
        logger.setLevel(Level.FINE);
        logger.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("%tF %<tT [%s] %s - %s%s%n",
                        record.getMillis(), record.getLevel(), record.getLoggerName(),
                        record.getMessage(), record.getThrown() != null ? " " + record.getThrown() : "");
            }
        });
        logger.addHandler(handler);
    }

    public ChatServer() {
        super(new InetSocketAddress("0.0.0.0", PORT));
    }

    public static void main(String[] args) {
        LOGGER.info("Starting WebSocket server on port " + PORT + "...");
        ChatServer server = new ChatServer();
        server.start();
        LOGGER.info("WebSocket server is up and running");
        startCommandListener();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("New WebSocket connection: " + conn.getRemoteSocketAddress());
        clients.add(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("Connection closed: " + conn.getRemoteSocketAddress() + " (Code: " + code + ", Reason: " + reason + ")");
        clients.remove(conn);
        lastMessageTime.remove(conn);
        if (usernames.containsKey(conn)) {
            userConnections.remove(usernames.get(conn).toLowerCase());
            usernames.remove(conn);
        }
        lastWhisperFrom.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.equalsIgnoreCase("ping")) {
            conn.send("pong");
            LOGGER.fine("Received ping from " + conn.getRemoteSocketAddress() + ", sent pong");
            return;
        }

        try {
            Gson gson = new Gson();
            Message incoming = gson.fromJson(message, Message.class);
            String username = incoming.getUsername() != null ? incoming.getUsername() : "Unknown";
            usernames.put(conn, username);
            userConnections.put(username.toLowerCase(), conn);

            if (incoming.getContent() != null && incoming.getContent().length() > MAX_MESSAGE_LENGTH) {
                String truncated = incoming.getContent().substring(0, MAX_MESSAGE_LENGTH);
                incoming = new Message(username, truncated, null);
                conn.send(gson.toJson(new Message("[System]", "Your message was too long and was truncated.", "§e[System]§r")));
                LOGGER.warning("Truncated long message from " + username);
            }

            long now = System.currentTimeMillis();
            Long last = lastMessageTime.get(conn);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                conn.send(gson.toJson(new Message("[System]", "You are sending messages too quickly. Please slow down.", "§e[System]§r")));
                LOGGER.warning("Rate limit exceeded by " + username);
                return;
            }
            lastMessageTime.put(conn, now);

            String content = incoming.getContent();
            if (content != null) {
                String lowerContent = content.toLowerCase();

                if (lowerContent.startsWith("/w ") || lowerContent.startsWith("/whisper ")) {
                    String[] parts = content.split(" ", 3);
                    if (parts.length < 3) {
                        conn.send(gson.toJson(new Message("[System]", "Usage: /w <username> <message>", "§e[System]§r")));
                        return;
                    }
                    String target = parts[1];
                    String whisper = parts[2];
                    WebSocket targetConn = userConnections.get(target.toLowerCase());
                    if (targetConn != null && targetConn.isOpen()) {
                        Message toTarget = new Message("[Whisper] " + username, whisper, "§d[Whisper] " + username + "§r");
                        targetConn.send(gson.toJson(toTarget));
                        lastWhisperFrom.put(targetConn, username);
                        lastWhisperFrom.put(conn, target);
                    } else {
                        conn.send(gson.toJson(new Message("[System]", "User '" + target + "' not found or not online.", "§e[System]§r")));
                    }
                    return;
                }

                if (lowerContent.startsWith("/r ") || lowerContent.startsWith("/reply ")) {
                    String[] parts = content.split(" ", 2);
                    if (parts.length < 2) {
                        conn.send(gson.toJson(new Message("[System]", "Usage: /r <message>", "§e[System]§r")));
                        return;
                    }
                    String replyMsg = parts[1];
                    String target = lastWhisperFrom.get(conn);
                    if (target != null) {
                        WebSocket targetConn = userConnections.get(target.toLowerCase());
                        if (targetConn != null && targetConn.isOpen()) {
                            Message toTarget = new Message("[Whisper] " + username, replyMsg, "§d[Whisper] " + username + "§r");
                            targetConn.send(gson.toJson(toTarget));
                            lastWhisperFrom.put(targetConn, username);
                            return;
                        }
                    }
                    conn.send(gson.toJson(new Message("[System]", "No recent user to reply to.", "§e[System]§r")));
                    return;
                }
            }

            String coloredUsername = UserColorManager.getColoredUsername(username);
            Message colored = new Message(username, content, coloredUsername);
            String json = gson.toJson(colored);

            for (WebSocket client : clients) {
                if (client.isOpen()) {
                    client.send(json);
                }
            }

            LOGGER.fine("Message from " + username + ": " + content);
        } catch (Exception e) {
            LOGGER.warning("Failed to process message: " + message + " — " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.log(Level.SEVERE, "WebSocket error" + (conn != null ? " from " + conn.getRemoteSocketAddress() : ""), ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("Server started successfully on port " + PORT);
    }

    private static void startCommandListener() {
        new Thread(() -> {
            try (var console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = console.readLine()) != null) {
                    if (line.equalsIgnoreCase("/shutdown")) {
                        shutdown();
                    } else if (line.equalsIgnoreCase("/users")) {
                        LOGGER.info("Connected clients: " + clients.size());
                    } else if (line.startsWith("/broadcast ")) {
                        String text = line.substring(11).trim();
                        Message broadcastMsg = new Message("[System]", text, "§e[System]§r");
                        String json = new Gson().toJson(broadcastMsg);
                        for (WebSocket client : clients) {
                            client.send(json);
                        }
                        LOGGER.info("Broadcast sent: " + text);
                    } else {
                        LOGGER.warning("Unknown command: " + line);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Command listener error", e);
            }
        }, "CommandListener").start();
    }

    public static void shutdown() {
        LOGGER.info("Shutting down...");
        for (WebSocket client : clients) {
            client.close(1000, "Server shutdown");
        }
        System.exit(0);
    }
}
