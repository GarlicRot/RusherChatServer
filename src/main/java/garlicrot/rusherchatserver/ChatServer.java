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

    // --- Configuration ---
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());

    private static final int DEFAULT_PORT = 42424;
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final long MIN_INTERVAL_MS = 1000;

    // --- Shared state ---
    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();

    private final Map<WebSocket, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    // username (lowercase) -> connection
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    // username (lowercase) -> base64 public key
    private final Map<String, String> userPublicKeys = new ConcurrentHashMap<>();

    private final Gson gson = new Gson();
    private final int port;

    // --- Logging setup ---
    static {
        Logger rootLogger = Logger.getLogger("garlicrot.rusherchatserver");
        rootLogger.setLevel(Level.INFO);
        rootLogger.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%tF %<tT [%s] %s - %s%s%n",
                        record.getMillis(),
                        record.getLevel(),
                        record.getLoggerName(),
                        record.getMessage(),
                        record.getThrown() != null ? " " + record.getThrown() : ""
                );
            }
        });
        rootLogger.addHandler(handler);
    }

    // --- Constructors ---

    public ChatServer() {
        this(DEFAULT_PORT);
    }

    public ChatServer(int port) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.port = port;
    }

    // --- Main entrypoint ---

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid port argument '" + args[0] + "', falling back to default: " + DEFAULT_PORT);
            }
        }

        LOGGER.info("Starting WebSocket server on port " + port + "...");
        ChatServer server = new ChatServer(port);
        server.start();
        LOGGER.info("WebSocket server is up and running");
        startCommandListener();
    }

    // --- WebSocketServer overrides ---

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("New WebSocket connection: " + conn.getRemoteSocketAddress());
        clients.add(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("Connection closed: " + conn.getRemoteSocketAddress()
                + " (Code: " + code + ", Reason: " + reason + ", Remote: " + remote + ")");

        clients.remove(conn);
        lastMessageTime.remove(conn);

        String username = usernames.remove(conn);
        if (username != null) {
            String keyLower = username.toLowerCase();
            userConnections.remove(keyLower);
            userPublicKeys.remove(keyLower);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // ping/pong health check
        if ("ping".equalsIgnoreCase(message)) {
            conn.send("pong");
            return;
        }

        try {
            Message incoming = gson.fromJson(message, Message.class);
            if (incoming == null) {
                LOGGER.warning("Received null/invalid JSON message: " + message);
                sendSystemMessage(conn, "Invalid message format.");
                return;
            }

            Message.Type type = incoming.getType();

            // --- LOGIN handshake ---
            if (type == Message.Type.LOGIN) {
                handleLogin(conn, incoming);
                return;
            }

            // From here on, only allow messages from logged-in connections.
            String username = usernames.get(conn);
            if (username == null) {
                sendSystemMessage(conn, "You must log in before sending messages.");
                return;
            }

            // Rate limit (applies to CHAT + WHISPER)
            long now = System.currentTimeMillis();
            Long last = lastMessageTime.get(conn);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                sendSystemMessage(conn, "You are sending messages too quickly. Please slow down.");
                LOGGER.warning("Rate limit exceeded by " + username);
                return;
            }
            lastMessageTime.put(conn, now);

            switch (type) {
                case CHAT -> handleChat(username, incoming);
                case WHISPER -> handleWhisperPacket(conn, username, incoming);
                case SYSTEM -> LOGGER.fine("Ignoring client SYSTEM message from " + username);
                case LOGIN -> {
                    // already handled above
                }
                default -> LOGGER.warning("Unknown message type from " + username + ": " + type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to process message: " + message + " — " + e.getMessage(), e);
            sendSystemMessage(conn, "An error occurred while processing your message.");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String connInfo = (conn != null ? " from " + conn.getRemoteSocketAddress() : "");
        LOGGER.log(Level.SEVERE, "WebSocket error" + connInfo, ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("Server started successfully on port " + port);
        setConnectionLostTimeout(60);
    }

    // --- Command-line console listener (/shutdown, /users, /broadcast) ---

    private static void startCommandListener() {
        new Thread(() -> {
            try (var console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = console.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    if (line.equalsIgnoreCase("/shutdown")) {
                        LOGGER.info("Shutdown command received from console.");
                        shutdown();
                    } else if (line.equalsIgnoreCase("/users")) {
                        LOGGER.info("Connected clients: " + clients.size());
                    } else if (line.startsWith("/broadcast ")) {
                        String text = line.substring("/broadcast ".length()).trim();
                        if (!text.isEmpty()) {
                            Message broadcastMsg = new Message(
                                    Message.Type.SYSTEM,
                                    "[System]",
                                    text,
                                    "§e[System]§r",
                                    null,
                                    false
                            );
                            String json = new Gson().toJson(broadcastMsg);
                            broadcastToAll(json);
                            LOGGER.info("Broadcast sent: " + text);
                        } else {
                            LOGGER.warning("Broadcast command used with empty message.");
                        }
                    } else {
                        LOGGER.warning("Unknown console command: " + line);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Command listener error", e);
            }
        }, "CommandListener").start();
    }

    // --- Login / key distribution ---

    private void handleLogin(WebSocket conn, Message incoming) {
        String requestedName = incoming.getUsername();

        if (requestedName == null || requestedName.isBlank()) {
            sendSystemMessage(conn, "Username cannot be empty.");
            conn.close(1008, "Invalid username");
            return;
        }

        String keyLower = requestedName.toLowerCase();
        if (userConnections.containsKey(keyLower)) {
            sendSystemMessage(conn, "Username '" + requestedName + "' is already in use.");
            conn.close(1008, "Name in use");
            return;
        }

        usernames.put(conn, requestedName);
        userConnections.put(keyLower, conn);

        String publicKeyB64 = incoming.getPublicKey();

        if (publicKeyB64 != null) {
            LOGGER.info("LOGIN from " + requestedName + " with publicKey length=" + publicKeyB64.length());
        } else {
            LOGGER.info("LOGIN from " + requestedName + " with publicKey=null");
        }

        if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
            // store by lowercase username for consistency
            userPublicKeys.put(keyLower, publicKeyB64);

            // 1) Send all known keys (including this one) to the newly logged-in client
            for (Map.Entry<String, String> entry : userPublicKeys.entrySet()) {
                String nameLower = entry.getKey();
                String key = entry.getValue();
                String content = "USER_KEY:" + nameLower + ":" + key;

                Message sys = new Message(
                        Message.Type.SYSTEM,
                        "[System]",
                        content,
                        "§e[System]§r",
                        null,
                        false
                );
                conn.send(gson.toJson(sys));
            }

            // 2) Broadcast this user's key to everyone else
            String newKeyContent = "USER_KEY:" + requestedName + ":" + publicKeyB64;
            Message keyAnnouncement = new Message(
                    Message.Type.SYSTEM,
                    "[System]",
                    newKeyContent,
                    "§e[System]§r",
                    null,
                    false
            );
            broadcastToAllExcept(conn, gson.toJson(keyAnnouncement));

            LOGGER.info("Stored public key for " + requestedName + " and distributed to clients");
        } else {

            LOGGER.warning("Client " + requestedName + " did not provide a public key; E2EE whispers will fall back to non-functional.");
        }

        LOGGER.info("User logged in: " + requestedName + " from " + conn.getRemoteSocketAddress());
    }

    // --- Chat / whisper routing ---

    private void handleChat(String username, Message incoming) {
        String content = incoming.getContent();
        if (content == null || content.trim().isEmpty()) {
            LOGGER.fine("Skipping empty or null chat message from " + username);
            return;
        }

        String trimmed = content.trim();

        // Enforce chat message length (OK to truncate here – it's plain text)
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_LENGTH);
            sendSystemMessage(userConnections.get(username.toLowerCase()),
                    "Your message was too long and was truncated.");
            LOGGER.warning("Truncated long message from " + username);
        }

        String coloredUsername = UserColorManager.getColoredUsername(username);
        Message colored = new Message(
                Message.Type.CHAT,
                username,
                trimmed,
                coloredUsername,
                null,
                false
        );
        String json = gson.toJson(colored);

        broadcastToAll(json);
        LOGGER.info("Message from " + username + ": " + trimmed);
    }

    /**
     * E2EE-friendly whisper routing:
     * - content is already encrypted by the client
     * - server does not encrypt or decrypt
     * - server only routes based on `target` and `username`
     */
    private void handleWhisperPacket(WebSocket senderConn, String senderName, Message incoming) {
        String targetName = incoming.getTarget();
        String cipherText = incoming.getContent();

        if (targetName == null || targetName.isBlank()) {
            sendSystemMessage(senderConn, "Whisper target missing.");
            return;
        }

        if (cipherText == null || cipherText.isBlank()) {
            sendSystemMessage(senderConn, "Whisper message is empty.");
            return;
        }

        WebSocket targetConn = userConnections.get(targetName.toLowerCase());
        if (targetConn != null && targetConn.isOpen()) {
            // NOTE: Do NOT modify cipherText; client expects it intact.
            Message toTarget = new Message(
                    Message.Type.WHISPER,
                    "[Whisper] " + senderName,
                    cipherText,
                    "§d[Whisper] " + senderName + "§r",
                    senderName,
                    true
            );

            // Send ONLY to the target – sender already has a local plaintext echo
            targetConn.send(gson.toJson(toTarget));

            LOGGER.info("WHISPER routed: " + senderName + " -> " + targetName
                    + " (" + cipherText.length() + " chars, opaque to server)");
        } else {
            sendSystemMessage(senderConn, "User '" + targetName + "' not found or not online.");
        }
    }

    // --- Broadcast helpers ---

    private static void broadcastToAll(String json) {
        for (WebSocket client : clients) {
            if (client.isOpen()) {
                client.send(json);
            }
        }
    }

    private static void broadcastToAllExcept(WebSocket exclude, String json) {
        for (WebSocket client : clients) {
            if (client != exclude && client.isOpen()) {
                client.send(json);
            }
        }
    }

    private void sendSystemMessage(WebSocket conn, String text) {
        if (conn == null) {
            return;
        }
        Message sys = new Message(
                Message.Type.SYSTEM,
                "[System]",
                text,
                "§e[System]§r",
                null,
                false
        );
        conn.send(gson.toJson(sys));
    }

    public static void shutdown() {
        LOGGER.info("Shutting down server and closing all connections...");
        for (WebSocket client : clients) {
            try {
                client.close(1000, "Server shutdown");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to close client connection cleanly", e);
            }
        }
        System.exit(0);
    }
}
