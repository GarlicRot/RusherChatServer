package garlicrot.rusherchatserver;

public class Message {
    private String username;
    private String content;
    private String coloredUsername;
    private String target;
    private boolean whisper;

    public Message() {}

    public Message(String username, String content) {
        this.username = username;
        this.content = content;
        this.coloredUsername = null;
        this.target = null;
        this.whisper = false;
    }

    public Message(String username, String content, String coloredUsername) {
        this.username = username;
        this.content = content;
        this.coloredUsername = coloredUsername;
        this.target = null;
        this.whisper = false;
    }

    public Message(String username, String content, String coloredUsername, String target, boolean whisper) {
        this.username = username;
        this.content = content;
        this.coloredUsername = coloredUsername;
        this.target = target;
        this.whisper = whisper;
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

    public String getTarget() {
        return target;
    }

    public boolean isWhisper() {
        return whisper;
    }

    public void setColoredUsername(String coloredUsername) {
        this.coloredUsername = coloredUsername;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public void setWhisper(boolean whisper) {
        this.whisper = whisper;
    }
}