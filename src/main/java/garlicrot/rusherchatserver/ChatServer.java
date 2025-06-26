package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatServer extends WebSocketServer {
    private static final int PORT = 42424;
    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();
    private static final List<String> messageHistory = new CopyOnWriteArrayList<>();
    private static final int HISTORY_LIMIT = 50;

    public ChatServer() {
        super(new InetSocketAddress(PORT));
    }

    public static void main(String[] args) {
        System.out.println("[RusherChatServer] Starting WebSocket server on port " + PORT + "...");

        ChatServer server = new ChatServer();
        server.start();
        System.out.println("[RusherChatServer] WebSocket server is up and running.");

        startCommandListener();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[RusherChatServer] New WebSocket connection: " + conn.getRemoteSocketAddress());
        clients.add(conn);

        // Send history to new client
        for (String msg : messageHistory) {
            conn.send(msg);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[RusherChatServer] Connection closed: " + conn.getRemoteSocketAddress());
        clients.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        messageHistory.add(message);
        if (messageHistory.size() > HISTORY_LIMIT) {
            messageHistory.removeFirst();
        }

        for (WebSocket client : clients) {
            if (client != conn && client.isOpen()) {
                client.send(message);
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[RusherChatServer] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[RusherChatServer] Server started successfully.");
    }

    private static void startCommandListener() {
        new Thread(() -> {
            try (var console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = console.readLine()) != null) {
                    if (line.equalsIgnoreCase("/shutdown")) {
                        shutdown();
                    } else if (line.equalsIgnoreCase("/users")) {
                        System.out.println("[Server] Connected clients: " + clients.size());
                    } else if (line.startsWith("/broadcast ")) {
                        String text = line.substring(11).trim();
                        String json = new Gson().toJson(new Message("[System]", text));
                        addToHistory(json);
                        for (WebSocket client : clients) {
                            client.send(json);
                        }
                        System.out.println("[Server] Broadcast sent.");
                    } else {
                        System.out.println("[Server] Unknown command.");
                    }
                }
            } catch (Exception e) {
                System.err.println("[Server] Command listener error: " + e.getMessage());
            }
        }, "CommandListener").start();
    }

    private static void addToHistory(String msg) {
        messageHistory.add(msg);
        if (messageHistory.size() > HISTORY_LIMIT) {
            messageHistory.removeFirst();
        }
    }

    public static void shutdown() {
        System.out.println("[RusherChatServer] Shutting down...");
        boolean running = false;
        for (WebSocket client : clients) {
            client.close(1000, "Server shutdown");
        }
        System.exit(0);
    }
}
