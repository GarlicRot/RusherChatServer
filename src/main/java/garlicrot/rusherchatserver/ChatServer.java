package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.util.List;
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
    private static final List<String> messageHistory = new CopyOnWriteArrayList<>();
    private static final int HISTORY_LIMIT = 50;

    static {
        // Configure JUL programmatically
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

        // Send history to new client
        for (String msg : messageHistory) {
            conn.send(msg);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("Connection closed: " + conn.getRemoteSocketAddress() + " (Code: " + code + ", Reason: " + reason + ")");
        clients.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.equalsIgnoreCase("ping")) {
            conn.send("pong");
            LOGGER.fine("Received ping from " + conn.getRemoteSocketAddress() + ", sent pong");
            return;
        }

        if (messageHistory.size() > HISTORY_LIMIT) {
            messageHistory.removeFirst();
        }

        try {
            Gson gson = new Gson();
            Message incoming = gson.fromJson(message, Message.class);
            String rawUsername = incoming.getUsername() != null ? incoming.getUsername() : "Unknown";
            String coloredUsername = UserColorManager.getColoredUsername(rawUsername);
            Message colored = new Message(rawUsername, incoming.getContent(), coloredUsername);
            String coloredJson = gson.toJson(colored);

            addToHistory(coloredJson);

            for (WebSocket client : clients) {
                if (client != conn && client.isOpen()) {
                    client.send(coloredJson);
                }
            }

            LOGGER.fine("Message from " + rawUsername + ": " + incoming.getContent());
        } catch (Exception e) {
            LOGGER.warning("Failed to process message: " + message + " — " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.log(Level.SEVERE, "WebSocket error" + (conn != null ? " from " + conn.getRemoteSocketAddress() : ""), ex); // Replaced printStackTrace
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
                        String json = new Gson().toJson(new Message("[System]", text));
                        addToHistory(json);
                        for (WebSocket client : clients) {
                            client.send(json);
                        }
                        LOGGER.info("Broadcast sent: " + text);
                    } else {
                        LOGGER.warning("Unknown command: " + line);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Command listener error", e); // Replaced printStackTrace
            }
        }, "CommandListener").start();
    }

    private static void addToHistory(String msg) {
        if (messageHistory.size() >= HISTORY_LIMIT) {
            messageHistory.removeFirst();
        }
        messageHistory.add(msg);
    }


    public static void shutdown() {
        LOGGER.info("Shutting down...");
        for (WebSocket client : clients) {
            client.close(1000, "Server shutdown");
        }
        System.exit(0);
    }
}