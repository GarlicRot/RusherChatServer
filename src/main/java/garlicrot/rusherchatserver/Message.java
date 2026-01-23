package garlicrot.rusherchatserver;

public class Message {

    public enum Type {
        LOGIN,   // initial handshake to bind username
        CHAT,    // normal public chat
        SYSTEM,  // server/system messages
        WHISPER  // private messages
    }

    private Type type;
    private String username;
    private String content;
    private String coloredUsername;
    private String target;
    private boolean whisper;

    // Required no-arg constructor for Gson
    public Message() {
        this.type = Type.CHAT;
    }

    // Convenience constructors

    public Message(String username, String content) {
        this(Type.CHAT, username, content, null, null, false);
    }

    public Message(String username, String content, String coloredUsername) {
        this(Type.CHAT, username, content, coloredUsername, null, false);
    }

    public Message(String username, String content, String coloredUsername, String target, boolean whisper) {
        this(Type.CHAT, username, content, coloredUsername, target, whisper);
    }

    public Message(Type type,
                   String username,
                   String content,
                   String coloredUsername,
                   String target,
                   boolean whisper) {
        this.type = (type != null ? type : Type.CHAT);
        this.username = username;
        this.content = content;
        this.coloredUsername = coloredUsername;
        this.target = target;
        this.whisper = whisper;
    }

    public Type getType() {
        return type;
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

    public void setType(Type type) {
        this.type = type;
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
