package garlicrot.rusherchatserver;

import java.util.HashMap;
import java.util.Map;

public class UserColorManager {
    private static final Map<String, String> userColors = new HashMap<>();

    static {
        // Define your static color mappings here
        userColors.put("GARLICROT", "§6");
        userColors.put("JOHN200410", "§6");
    }

    public static String getColoredUsername(String username) {
        if (username == null || username.isBlank()) return "§7Unknown§r";
        String color = userColors.getOrDefault(username.toUpperCase(), "§7"); // Default to gray
        return color + username + "§r";
    }
}
