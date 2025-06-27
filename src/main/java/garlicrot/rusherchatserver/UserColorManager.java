package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class UserColorManager {
    private static final String FILE_NAME = "user_colors.json";
    private static final Gson gson = new Gson();
    private static Map<String, String> userColors = Collections.emptyMap();

    static {
        reload();
    }

    public static void reload() {
        try (FileReader reader = new FileReader(FILE_NAME)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = gson.fromJson(reader, type);

            if (loaded != null && !loaded.isEmpty()) {
                Map<String, String> temp = new HashMap<>();
                for (Map.Entry<String, String> entry : loaded.entrySet()) {
                    String username = entry.getKey();
                    String colorCode = entry.getValue();
                    if (username != null && colorCode != null) {
                        temp.put(username.toUpperCase(), colorCode);
                    }
                }
                userColors = temp;
                System.out.println("[RusherChatServer] Loaded " + userColors.size() + " user color(s) from " + FILE_NAME);
            } else {
                userColors = Collections.emptyMap();
                System.err.println("[RusherChatServer] user_colors.json is empty or invalid.");
            }
        } catch (IOException e) {
            System.err.println("[RusherChatServer] Failed to load " + FILE_NAME + ": " + e.getMessage());
            userColors = Collections.emptyMap();
        }
    }

    public static String getColoredUsername(String username) {
        if (username == null || username.isBlank()) return "§7Unknown§r";
        String color = userColors.getOrDefault(username.toUpperCase(), "§7"); // Default gray
        return color + username + "§r";
    }
}
