package garlicrot.rusherchatserver;

public class Message {
    private String username;
    private String content;
    private String coloredUsername;

    public Message() {}

    public Message(String username, String content) {
        this.username = username;
        this.content = content;
        this.coloredUsername = username; // Default to plain username if color not set
    }

    public Message(String username, String content, String coloredUsername) {
        this.username = username;
        this.content = content;
        this.coloredUsername = coloredUsername;
    }

    public String getUsername() {
        return username;
    }

    public String getContent() {
        return content;
    }

    public String getColoredUsername() {
        return coloredUsername;
    }

    public void setColoredUsername(String coloredUsername) {
        this.coloredUsername = coloredUsername;
    }
}
