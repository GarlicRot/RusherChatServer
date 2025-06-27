package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class ChatServer extends WebSocketServer {
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());
    private static final int PORT = 42424;
    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();
    private static final Map<WebSocket, String> clientUsernames = new ConcurrentHashMap<>(); // Map WebSocket to username

    static {
        Logger logger = Logger.getLogger("garlicrot.rusherchatserver");
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
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
        // Check for handshake parameter first
        String username = handshake.getFieldValue("username");
        if (username != null && !username.isEmpty()) {
            clientUsernames.put(conn, username);
            LOGGER.info("Registered username: " + username + " for connection " + conn.getRemoteSocketAddress());
        } else {
            LOGGER.info("No username provided in handshake, waiting for initial message from " + conn.getRemoteSocketAddress());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("Connection closed: " + conn.getRemoteSocketAddress() + " (Code: " + code + ", Reason: " + reason + ")");
        clients.remove(conn);
        clientUsernames.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.equalsIgnoreCase("ping")) {
            conn.send("pong");
            LOGGER.info("Received ping from " + conn.getRemoteSocketAddress() + ", sent pong");
            return;
        }

        try {
            Gson gson = new Gson();
            Message incoming = gson.fromJson(message, Message.class);
            String rawUsername = incoming.getUsername() != null ? incoming.getUsername() : "Unknown";

            // Register username if not yet set and this is the initial message (empty content)
            if (clientUsernames.get(conn) == null && incoming.getContent() != null && incoming.getContent().isEmpty()) {
                clientUsernames.put(conn, rawUsername);
                LOGGER.info("Registered username: " + rawUsername + " for connection " + conn.getRemoteSocketAddress());
                return; // Skip further processing for registration message
            }

            String coloredUsername = UserColorManager.getColoredUsername(rawUsername);
            Message colored = new Message(rawUsername, incoming.getContent(), coloredUsername, incoming.getTarget(), incoming.isWhisper());

            if (incoming.isWhisper() && incoming.getTarget() != null) {
                // Find the target client
                String targetLower = incoming.getTarget().toLowerCase();
                boolean sentToTarget = false;
                for (WebSocket client : clients) {
                    String clientUsername = clientUsernames.get(client);
                    if (clientUsername != null && clientUsername.toLowerCase().equals(targetLower) && client.isOpen() && client != conn) {
                        client.send(gson.toJson(colored));
                        sentToTarget = true;
                    }
                }
                // Send to sender for confirmation
                if (sentToTarget) {
                    conn.send(gson.toJson(colored));
                } else {
                    conn.send(gson.toJson(new Message("[System]", "User " + incoming.getTarget() + " not found.", "§e[System]§r")));
                }
            } else {
                // Broadcast to all except sender
                for (WebSocket client : clients) {
                    if (client != conn && client.isOpen()) {
                        client.send(gson.toJson(colored));
                    }
                }
            }

            LOGGER.info("Message from " + rawUsername + ": " + incoming.getContent() + (incoming.isWhisper() ? " (whisper to " + incoming.getTarget() + ")" : ""));
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