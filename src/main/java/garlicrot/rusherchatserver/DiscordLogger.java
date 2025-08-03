package garlicrot.rusherchatserver;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DiscordLogger {
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1401394269432053813/Dcy_aUwXLD00qAagkiVXnfEaXDKhRuWm3biupYB05qfC2dGFPdiaBQ4qRPKb_PpqhrAa";

    public static void send(String message) {
        try {
            URL url = new URL(WEBHOOK_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String json = "{\"content\": \"" + escape(message) + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
            }

            conn.getInputStream().close(); // trigger the request
        } catch (Exception e) {
            System.err.println("Discord logging failed: " + e.getMessage());
        }
    }

    private static String escape(String input) {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "");
    }
}
