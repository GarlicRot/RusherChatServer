package org.rusherchat.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ChatServer {
    private static final int PORT = 12345;
    private static final Set<ClientHandler> clientHandlers = new HashSet<>();
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());

    // Define ranks and their corresponding colors
    private static final Map<String, String> USER_RANKS = new HashMap<>();
    static {
        USER_RANKS.put("GARLICROT", "§a"); // Green (Plugin Dev)
    }

    public static void main(String[] args) {
        LOGGER.log(Level.INFO, "Chat server started...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clientHandlers.add(clientHandler);
                new Thread(clientHandler).start();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in server", e);
        }
    }

    // Method to broadcast messages to all connected clients
    public static void broadcastMessage(String message) {
        for (ClientHandler handler : clientHandlers) {
            handler.sendMessage(message);
        }
    }

    // Nested class to handle individual client connections
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private String username;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void sendMessage(String message) {
            if (out != null) {
                out.println(message);
            }
        }

        public void run() {
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                this.out = out;

                // First message from the client should be the username
                this.username = in.readLine();

                // Log when the user joins the chat
                LOGGER.log(Level.INFO, "{0} has connected.", username);

                String message;
                while ((message = in.readLine()) != null) {
                    // Now handle chat messages as usual
                    String text = message.trim();

                    // Assign color based on rank
                    String colorCode = USER_RANKS.getOrDefault(username, "§9"); // Blue for players
                    String coloredMessage = colorCode + username + "§r: §f" + text;
                    LOGGER.log(Level.INFO, "Received: {0}", coloredMessage);
                    ChatServer.broadcastMessage(coloredMessage);
                }
            } catch (SocketException e) {
                // Handle the client disconnecting gracefully
                LOGGER.log(Level.INFO, "{0} has disconnected.", username);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error handling client", e);
            } finally {
                // Clean up
                try {
                    socket.close();
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error closing socket", e);
                }
            }
        }
    }
}
