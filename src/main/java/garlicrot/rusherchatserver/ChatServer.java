package garlicrot.rusherchatserver;

import com.google.gson.Gson;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatServer {
    private static final int PORT = 42424;
    private static final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private static final List<String> messageHistory = new CopyOnWriteArrayList<>();
    private static final int HISTORY_LIMIT = 50;
    private static boolean running = true;

    public static void main(String[] args) {
        System.out.println("[RusherChatServer] Starting server on port " + PORT + "...");

        // Start console command listener
        startCommandListener();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[RusherChatServer] Server is up and listening for connections.");

            while (running) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[RusherChatServer] New connection: " + clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(clientSocket, clients);
                clients.add(handler);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            System.err.println("[RusherChatServer] Server error: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    private static void startCommandListener() {
        new Thread(() -> {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            String line;

            try {
                while ((line = console.readLine()) != null) {
                    if (line.equalsIgnoreCase("/users")) {
                        System.out.println("[Server] Connected users:");
                        for (ClientHandler client : clients) {
                            System.out.println(" - " + client.getUsername());
                        }
                    } else if (line.startsWith("/broadcast ")) {
                        String message = line.substring(11).trim();
                        String json = new Gson().toJson(new Message("[System]", message));
                        addToHistory(json);
                        for (ClientHandler client : clients) {
                            client.send(json);
                        }
                        System.out.println("[Server] Broadcast sent.");
                    } else if (line.equalsIgnoreCase("/shutdown")) {
                        shutdown();
                    } else if (line.equalsIgnoreCase("/reloadcolors")) {
                        UserColorManager.reload();
                        System.out.println("[Server] Reloaded user colors.");
                    } else {
                        System.out.println("[Server] Unknown command.");
                    }
                }
            } catch (IOException e) {
                System.err.println("[Server] Command listener error: " + e.getMessage());
            }
        }, "ServerCommandListener").start();
    }

    public static List<String> getMessageHistory() {
        return messageHistory;
    }

    public static void addToHistory(String msg) {
        messageHistory.add(msg);
        if (messageHistory.size() > HISTORY_LIMIT) {
            messageHistory.removeFirst();
        }
    }

    public static void shutdown() {
        System.out.println("[RusherChatServer] Shutting down...");
        running = false;
        for (ClientHandler handler : clients) {
            handler.close();
        }
        System.exit(0);
    }
}
