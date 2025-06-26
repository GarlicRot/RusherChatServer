package garlicrot.rusherchatserver;

public class Message {
    private String username;
    private String content;

    public Message() {}

    public Message(String username, String content) {
        this.username = username;
        this.content = content;
    }

    public String getUsername() {
        return username;
    }

    public String getContent() {
        return content;
    }
}
