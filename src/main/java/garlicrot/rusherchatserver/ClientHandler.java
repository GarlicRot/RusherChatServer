package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final List<ClientHandler> clients;
    private PrintWriter out;
    private boolean active = true;
    private static final Gson gson = new Gson();
    private String username = "Unknown";

    public ClientHandler(Socket socket, List<ClientHandler> clients) {
        this.socket = socket;
        this.clients = clients;
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            sendHistory();

            String line;
            while (active && (line = in.readLine()) != null) {
                if (line.equalsIgnoreCase("ping")) {
                    send("pong");
                    continue;
                }

                try {
                    Message msg = gson.fromJson(line, Message.class);
                    this.username = msg.getUsername(); // store for /users, etc.

                    if (msg.getContent().startsWith("/")) {
                        handleCommand(msg.getContent());
                        continue;
                    }

                    String coloredUsername = UserColorManager.getColoredUsername(msg.getUsername());
                    Message formattedMsg = new Message(coloredUsername, msg.getContent());
                    String formattedJson = gson.toJson(formattedMsg);

                    ChatServer.addToHistory(formattedJson);
                    broadcast(formattedJson);
                } catch (JsonSyntaxException e) {
                    System.err.println("[RusherChatServer] Invalid JSON: " + line);
                }
            }
        } catch (IOException e) {
            System.out.println("[RusherChatServer] Connection lost: " + socket.getRemoteSocketAddress());
        } finally {
            close();
        }
    }

    private void handleCommand(String content) {
        String cmd = content.trim().toLowerCase();

        switch (cmd) {
            case "/help" -> sendJsonSystem("Available commands: /help, /list");
            case "/list" -> {
                StringBuilder sb = new StringBuilder("Online: ");
                for (ClientHandler c : clients) {
                    sb.append(c.getUsername()).append(", ");
                }
                sendJsonSystem(sb.substring(0, sb.length() - 2));
            }
            default -> sendJsonSystem("Unknown command: " + content);
        }
    }

    private void sendJsonSystem(String content) {
        Message sys = new Message("[System]", content);
        send(gson.toJson(sys));
    }

    private void sendHistory() {
        for (String msg : ChatServer.getMessageHistory()) {
            send(msg);
        }
    }

    private void broadcast(String msgJson) {
        for (ClientHandler client : clients) {
            if (client != this && client.active) {
                client.send(msgJson);
            }
        }
    }

    public void send(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    public void close() {
        active = false;
        clients.remove(this);
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    public String getUsername() {
        return username;
    }
}
