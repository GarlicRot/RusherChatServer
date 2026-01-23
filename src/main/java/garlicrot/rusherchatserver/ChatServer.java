package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
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

    // Must match client secret!
    private static final String WHISPER_SECRET = "rusherchat-whisper-key-01";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static SecretKeySpec WHISPER_KEY;

    // --- Shared state ---
    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();

    private final Map<WebSocket, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> lastWhisperFrom = new ConcurrentHashMap<>();

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

    // --- Encryption helpers ---

    private static SecretKeySpec getWhisperKey() throws Exception {
        if (WHISPER_KEY == null) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(WHISPER_SECRET.getBytes(StandardCharsets.UTF_8));
            WHISPER_KEY = new SecretKeySpec(key, "AES");
        }
        return WHISPER_KEY;
    }

    private static String encryptWhisper(String plainText) throws Exception {
        byte[] iv = new byte[12];
        SECURE_RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getWhisperKey(), new GCMParameterSpec(128, iv));
        byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + cipherBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

        return Base64.getEncoder().encodeToString(combined);
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

        if (usernames.containsKey(conn)) {
            String username = usernames.remove(conn);
            if (username != null) {
                userConnections.remove(username.toLowerCase());
            }
        }

        lastWhisperFrom.remove(conn);
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
                String requestedName = incoming.getUsername();

                if (requestedName == null || requestedName.isBlank()) {
                    sendSystemMessage(conn, "Username cannot be empty.");
                    conn.close(1008, "Invalid username");
                    return;
                }

                String key = requestedName.toLowerCase();
                if (userConnections.containsKey(key)) {
                    sendSystemMessage(conn, "Username '" + requestedName + "' is already in use.");
                    conn.close(1008, "Name in use");
                    return;
                }

                usernames.put(conn, requestedName);
                userConnections.put(key, conn);
                LOGGER.info("User logged in: " + requestedName + " from " + conn.getRemoteSocketAddress());
                return;
            }

            // From here on, only allow messages from logged-in connections.
            String username = usernames.get(conn);
            if (username == null) {
                sendSystemMessage(conn, "You must log in before sending messages.");
                return;
            }

            String content = incoming.getContent();
            if (content == null || content.trim().isEmpty()) {
                LOGGER.fine("Skipping empty or null message from " + username);
                return;
            }

            // Enforce message length
            if (content.length() > MAX_MESSAGE_LENGTH) {
                content = content.substring(0, MAX_MESSAGE_LENGTH);
                sendSystemMessage(conn, "Your message was too long and was truncated.");
                LOGGER.warning("Truncated long message from " + username);
            }

            // Rate limit
            long now = System.currentTimeMillis();
            Long last = lastMessageTime.get(conn);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                sendSystemMessage(conn, "You are sending messages too quickly. Please slow down.");
                LOGGER.warning("Rate limit exceeded by " + username);
                return;
            }
            lastMessageTime.put(conn, now);

            String trimmed = content.trim();
            String lowerContent = trimmed.toLowerCase();

            // --- Whisper commands ---
            if (lowerContent.startsWith("/w ") || lowerContent.startsWith("/whisper ")) {
                handleWhisper(conn, username, trimmed);
                return;
            }

            // --- Reply commands ---
            if (lowerContent.startsWith("/r ") || lowerContent.startsWith("/reply ")) {
                handleReply(conn, username, trimmed);
                return;
            }

            // --- Normal chat message ---
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

    // --- Helper methods ---

    private static void broadcastToAll(String json) {
        for (WebSocket client : clients) {
            if (client.isOpen()) {
                client.send(json);
            }
        }
    }

    private void sendSystemMessage(WebSocket conn, String text) {
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

    private void handleWhisper(WebSocket senderConn, String senderName, String content) {
        String[] parts = content.split(" ", 3);
        if (parts.length < 3) {
            sendSystemMessage(senderConn, "Usage: /w <username> <message>");
            return;
        }

        String targetName = parts[1];
        String whisperText = parts[2];

        WebSocket targetConn = userConnections.get(targetName.toLowerCase());
        if (targetConn != null && targetConn.isOpen()) {
            String encrypted;
            try {
                encrypted = encryptWhisper(whisperText);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to encrypt whisper", e);
                sendSystemMessage(senderConn, "Failed to send whisper (encryption error).");
                return;
            }

            Message toTarget = new Message(
                    Message.Type.WHISPER,
                    "[Whisper] " + senderName,
                    encrypted,
                    "§d[Whisper] " + senderName + "§r",
                    null,
                    true
            );
            Message toSender = new Message(
                    Message.Type.WHISPER,
                    "[Whisper ->] " + targetName,
                    encrypted,
                    "§5[Whisper ->] " + targetName + "§r",
                    null,
                    true
            );

            targetConn.send(gson.toJson(toTarget));
            senderConn.send(gson.toJson(toSender));

            lastWhisperFrom.put(targetConn, senderName);
            lastWhisperFrom.put(senderConn, targetName);

            LOGGER.info("Whisper: " + senderName + " -> " + targetName + " (" + whisperText.length() + " chars)");
        } else {
            sendSystemMessage(senderConn, "User '" + targetName + "' not found or not online.");
        }
    }

    private void handleReply(WebSocket senderConn, String senderName, String content) {
        String[] parts = content.split(" ", 2);
        if (parts.length < 2) {
            sendSystemMessage(senderConn, "Usage: /r <message>");
            return;
        }

        String replyMsg = parts[1];
        String targetName = lastWhisperFrom.get(senderConn);

        if (targetName == null) {
            sendSystemMessage(senderConn, "No recent user to reply to.");
            return;
        }

        WebSocket targetConn = userConnections.get(targetName.toLowerCase());
        if (targetConn != null && targetConn.isOpen()) {
            String encrypted;
            try {
                encrypted = encryptWhisper(replyMsg);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to encrypt whisper reply", e);
                sendSystemMessage(senderConn, "Failed to send whisper (encryption error).");
                return;
            }

            Message toTarget = new Message(
                    Message.Type.WHISPER,
                    "[Whisper] " + senderName,
                    encrypted,
                    "§d[Whisper] " + senderName + "§r",
                    null,
                    true
            );
            Message toSender = new Message(
                    Message.Type.WHISPER,
                    "[Whisper ->] " + targetName,
                    encrypted,
                    "§5[Whisper ->] " + targetName + "§r",
                    null,
                    true
            );

            targetConn.send(gson.toJson(toTarget));
            senderConn.send(gson.toJson(toSender));

            lastWhisperFrom.put(targetConn, senderName);

            LOGGER.info("Reply whisper: " + senderName + " -> " + targetName + " (" + replyMsg.length() + " chars)");
        } else {
            sendSystemMessage(senderConn, "User '" + targetName + "' not found or not online.");
        }
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
