package garlicrot.rusherchatserver;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

public class ChatServer extends WebSocketServer {

    // --- Configuration ---
    private static final Logger LOGGER = Logger.getLogger(ChatServer.class.getName());

    private static final int DEFAULT_PORT = 42424;
    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 256;
    private static final long DEFAULT_MIN_INTERVAL_MS = 1000;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_PUBLIC_KEY_LENGTH = 2048;
    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]+$";

    private final int maxMessageLength;
    private final long minIntervalMs;

    private static final String PLUGIN_REPO_OWNER = "GarlicRot";
    private static final String PLUGIN_REPO_NAME  = "RusherChat";
    private static final String GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/" + PLUGIN_REPO_OWNER + "/" + PLUGIN_REPO_NAME + "/releases/latest";

    private static final String OUTDATED_PREFIX = "OUTDATED_PLUGIN:";

    // Refresh cadence (few times a day) + stale TTL for on-demand background refresh
    private static final long LATEST_REFRESH_HOURS = 6;
    private static final long STALE_TTL_MINUTES = 15;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(6);

    // --- Shared state ---
    private static final List<WebSocket> clients = new CopyOnWriteArrayList<>();

    private final Map<WebSocket, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> usernames = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> userConnections = new ConcurrentHashMap<>(); // usernameLower -> conn
    private final Map<String, String> userPublicKeys = new ConcurrentHashMap<>();     // usernameLower -> pubKeyB64
    private final Map<WebSocket, String> clientVersions = new ConcurrentHashMap<>(); // conn -> clientVersion

    private final Gson gson = new Gson();
    private final int port;

    private static final LatestPluginInfoCache LATEST = new LatestPluginInfoCache(GITHUB_LATEST_RELEASE_API);

    // --- Logging setup ---
    static {
        Logger rootLogger = Logger.getLogger("garlicrot.rusherchatserver");
        rootLogger.setLevel(Level.INFO);
        rootLogger.setUseParentHandlers(false);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%tF %<tT [%s] %s - %s%s%n",
                        record.getMillis(),
                        record.getLevel(),
                        record.getLoggerName(),
                        record.getMessage(),
                        record.getThrown() != null ? " " + record.getThrown() : ""
                );
            }
        });
        rootLogger.addHandler(handler);
    }

    // --- Constructors ---
    public ChatServer() {
        this(DEFAULT_PORT);
    }

    public ChatServer(int port) {
        this(
                port,
                resolveIntEnv("RUSHERCHAT_MAX_MESSAGE_LENGTH", DEFAULT_MAX_MESSAGE_LENGTH, 1, 4096),
                resolveLongEnv("RUSHERCHAT_MIN_INTERVAL_MS", DEFAULT_MIN_INTERVAL_MS, 0, 60_000)
        );
    }

    public ChatServer(int port, int maxMessageLength, long minIntervalMs) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.port = port;
        this.maxMessageLength = maxMessageLength;
        this.minIntervalMs = minIntervalMs;
    }

    private static String detectServerVersion() {
        try {
            Package p = ChatServer.class.getPackage();
            if (p == null) return "dev";
            String v = p.getImplementationVersion();
            return (v != null && !v.isBlank()) ? v : "dev";
        } catch (Exception ignored) {
            return "dev";
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            return parsePortOrDefault(args[0], "command line argument");
        }

        String envPort = System.getenv("RUSHERCHAT_PORT");
        if (envPort != null && !envPort.isBlank()) {
            return parsePortOrDefault(envPort, "RUSHERCHAT_PORT");
        }

        return DEFAULT_PORT;
    }

    private static int parsePortOrDefault(String rawPort, String source) {
        try {
            int parsed = Integer.parseInt(rawPort.trim());
            if (parsed < 1 || parsed > 65535) {
                LOGGER.warning("Invalid port from " + source + " '" + rawPort + "', falling back to default: " + DEFAULT_PORT);
                return DEFAULT_PORT;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid port from " + source + " '" + rawPort + "', falling back to default: " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    private static int resolveIntEnv(String envName, int defaultValue, int min, int max) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min || parsed > max) {
                LOGGER.warning("Invalid " + envName + " '" + raw + "', falling back to default: " + defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + envName + " '" + raw + "', falling back to default: " + defaultValue);
            return defaultValue;
        }
    }

    private static long resolveLongEnv(String envName, long defaultValue, long min, long max) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < min || parsed > max) {
                LOGGER.warning("Invalid " + envName + " '" + raw + "', falling back to default: " + defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + envName + " '" + raw + "', falling back to default: " + defaultValue);
            return defaultValue;
        }
    }

    public static void main(String[] args) {
        int port = resolvePort(args);

        String version = detectServerVersion();
        LOGGER.info("RusherChatServer version: " + version);

        ChatServer server = new ChatServer(port);
        LOGGER.info("Server config: port=" + server.port
                + ", maxMessageLength=" + server.maxMessageLength
                + ", minIntervalMs=" + server.minIntervalMs);

        // Refresh latest plugin info periodically + on-demand (stale) refresh in background
        LATEST.startBackgroundRefresh();

        LOGGER.info("Starting WebSocket server on port " + port + "...");
        server.start();
        LOGGER.info("WebSocket server is up and running");

        startCommandListener(version);
    }

    // --- WebSocketServer overrides ---
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("New WebSocket connection: " + conn.getRemoteSocketAddress());
        clients.add(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("Connection closed: " + conn.getRemoteSocketAddress()
                + " (Code: " + code + ", Reason: " + reason + ", Remote: " + remote + ")");

        clients.remove(conn);
        lastMessageTime.remove(conn);
        clientVersions.remove(conn);

        String username = usernames.remove(conn);
        if (username != null) {
            String keyLower = username.toLowerCase();
            userConnections.remove(keyLower);
            userPublicKeys.remove(keyLower);
            broadcastOnlineList();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if ("ping".equalsIgnoreCase(message)) {
            conn.send("pong");
            return;
        }

        try {
            Message incoming = gson.fromJson(message, Message.class);
            if (incoming == null) {
                LOGGER.warning("Received null/invalid JSON message");
                sendSystemMessage(conn, "Invalid message format.");
                return;
            }

            Message.Type type = incoming.getType();

            if (type == Message.Type.LOGIN) {
                handleLogin(conn, incoming);
                return;
            }

            String username = usernames.get(conn);
            if (username == null) {
                sendSystemMessage(conn, "You must log in before sending messages.");
                return;
            }

            long now = System.currentTimeMillis();
            Long last = lastMessageTime.get(conn);
            if (last != null && now - last < minIntervalMs) {
                sendSystemMessage(conn, "You are sending messages too quickly. Please slow down.");
                LOGGER.fine("Rate limit exceeded by " + username);
                return;
            }
            lastMessageTime.put(conn, now);

            switch (type) {
                case CHAT -> handleChat(username, incoming);
                case WHISPER -> handleWhisperPacket(conn, username, incoming);
                case SYSTEM -> LOGGER.fine("Ignoring client SYSTEM message from " + username);
                case LOGIN -> { /* handled above */ }
                default -> LOGGER.warning("Unknown message type from " + username + ": " + type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to process incoming message", e);
            sendSystemMessage(conn, "An error occurred while processing your message.");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String connInfo = (conn != null ? " from " + conn.getRemoteSocketAddress() : "");
        LOGGER.log(Level.SEVERE, "WebSocket error" + connInfo, ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("Server started successfully on port " + port);
        setConnectionLostTimeout(60);
    }

    // --- Console commands (/shutdown, /users, /broadcast, /version, /plugin) ---
    private static void startCommandListener(String serverVersion) {
        new Thread(() -> {
            try (var console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = console.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.equalsIgnoreCase("/shutdown")) {
                        LOGGER.info("Shutdown command received from console.");
                        shutdown();
                    } else if (line.equalsIgnoreCase("/users")) {
                        LOGGER.info("Connected clients: " + clients.size());
                    } else if (line.equalsIgnoreCase("/version")) {
                        LOGGER.info("RusherChatServer version: " + serverVersion);
                    } else if (line.equalsIgnoreCase("/plugin")) {
                        LatestPluginInfo info = LATEST.get();
                        if (info == null || info.latestVersion == null) {
                            LOGGER.info("Latest plugin: unknown (not fetched yet)");
                        } else {
                            LOGGER.info("Latest plugin: " + info.latestVersion + " (" + info.htmlUrl + ")");
                        }
                    } else if (line.startsWith("/broadcast ")) {
                        String text = line.substring("/broadcast ".length()).trim();
                        if (!text.isEmpty()) {
                            Message broadcastMsg = new Message(
                                    Message.Type.SYSTEM,
                                    "[System]",
                                    text,
                                    "§e[System]§r",
                                    null,
                                    false
                            );
                            broadcastToAll(new Gson().toJson(broadcastMsg));
                            LOGGER.info("Broadcast sent: " + text);
                        } else {
                            LOGGER.warning("Broadcast command used with empty message.");
                        }
                    } else {
                        LOGGER.warning("Unknown console command: " + line);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Command listener error", e);
            }
        }, "CommandListener").start();
    }

    // --- Login / key distribution ---
    private void handleLogin(WebSocket conn, Message incoming) {
        String requestedName = incoming.getUsername();
        if (requestedName != null) {
            requestedName = requestedName.trim();
        }

        if (!isValidUsername(requestedName)) {
            sendSystemMessage(conn, usernameRulesMessage());
            conn.close(1008, "Invalid username");
            return;
        }

        String keyLower = requestedName.toLowerCase();
        if (userConnections.containsKey(keyLower)) {
            sendSystemMessage(conn, "Username '" + requestedName + "' is already in use.");
            conn.close(1008, "Name in use");
            return;
        }

        usernames.put(conn, requestedName);
        userConnections.put(keyLower, conn);

        String clientVersion = normalizeClientVersion(incoming.getClientVersion());
        clientVersions.put(conn, clientVersion);

        String publicKeyB64 = incoming.getPublicKey();
        if (publicKeyB64 != null) {
            publicKeyB64 = publicKeyB64.trim();
        }

        if (publicKeyB64 != null && publicKeyB64.length() > MAX_PUBLIC_KEY_LENGTH) {
            sendSystemMessage(conn, "Public key payload is too large.");
            conn.close(1008, "Invalid public key");
            return;
        }

        LOGGER.info("LOGIN " + requestedName
                + " (clientVersion=" + clientVersion + ", key=" + (publicKeyB64 != null && !publicKeyB64.isBlank() ? "yes" : "no") + ")");

        maybeWarnOutdatedPlugin(conn, clientVersion);

        if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
            userPublicKeys.put(keyLower, publicKeyB64);

            // Send all known keys to the new client
            for (Map.Entry<String, String> entry : userPublicKeys.entrySet()) {
                String nameLower2 = entry.getKey();
                String key = entry.getValue();
                String content = "USER_KEY:" + nameLower2 + ":" + key;

                Message sys = new Message(
                        Message.Type.SYSTEM,
                        "[System]",
                        content,
                        "§e[System]§r",
                        null,
                        false
                );
                conn.send(gson.toJson(sys));
            }

            // Broadcast this user's key to everyone else
            String newKeyContent = "USER_KEY:" + requestedName + ":" + publicKeyB64;
            Message keyAnnouncement = new Message(
                    Message.Type.SYSTEM,
                    "[System]",
                    newKeyContent,
                    "§e[System]§r",
                    null,
                    false
            );
            broadcastToAllExcept(conn, gson.toJson(keyAnnouncement));

            LOGGER.info("Stored public key for " + requestedName + " and distributed to clients");
        } else {
            LOGGER.fine("Client " + requestedName + " did not provide a public key; E2EE whispers may not work.");
        }

        broadcastOnlineList();
    }

    private static String normalizeClientVersion(String v) {
        if (v == null) return "unknown";
        v = v.trim();
        if (v.isEmpty()) return "unknown";
        if (v.equalsIgnoreCase("null")) return "unknown";
        return v;
    }

    private static boolean isValidUsername(String username) {
        if (username == null) return false;

        String trimmed = username.trim();
        return trimmed.length() >= MIN_USERNAME_LENGTH
                && trimmed.length() <= MAX_USERNAME_LENGTH
                && trimmed.matches(USERNAME_PATTERN);
    }

    private static String usernameRulesMessage() {
        return "Username must be 3-16 characters and use only letters, numbers, or underscore.";
    }

    private void maybeWarnOutdatedPlugin(WebSocket conn, String clientVersion) {
        LatestPluginInfo info = LATEST.getFresh();
        if (info == null || info.latestVersion == null || info.latestVersion.isBlank()) return;

        if ("dev".equalsIgnoreCase(clientVersion)) return;

        if ("unknown".equalsIgnoreCase(clientVersion)) {
            sendOutdated(conn, "unknown", info.latestVersion, info.htmlUrl);
            return;
        }

        int cmp = compareSemverLoose(clientVersion, info.latestVersion);
        if (cmp < 0) {
            sendOutdated(conn, clientVersion, info.latestVersion, info.htmlUrl);
        }
    }

    private void sendOutdated(WebSocket conn, String clientV, String latestV, String url) {
        sendSystemMessage(conn, OUTDATED_PREFIX + clientV + ":" + latestV + ":" + (url == null ? "" : url));

        sendSystemMessage(conn, "§cYour RusherChat plugin is outdated.");
        sendSystemMessage(conn, "§7Installed: §f" + clientV + " §7| Latest: §f" + latestV);
        if (url != null && !url.isBlank()) {
            sendSystemMessage(conn, "§7Download: §b" + url);
        }
    }

    // --- Version compare helpers ---
    private static int compareSemverLoose(String a, String b) {
        int[] av = parseVersionInts(a);
        int[] bv = parseVersionInts(b);
        for (int i = 0; i < 3; i++) {
            if (av[i] < bv[i]) return -1;
            if (av[i] > bv[i]) return 1;
        }
        return 0;
    }

    private static int[] parseVersionInts(String v) {
        int[] out = new int[]{0, 0, 0};
        if (v == null) return out;

        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        v = v.split("[-+]", 2)[0];

        String[] parts = v.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            String p = parts[i].replaceAll("[^0-9]", "");
            if (p.isEmpty()) continue;
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // --- Chat / whisper routing ---
    private void handleChat(String username, Message incoming) {
        String content = incoming.getContent();
        if (content == null || content.trim().isEmpty()) {
            LOGGER.fine("Skipping empty chat message from " + username);
            return;
        }

        String trimmed = content.trim();

        if (trimmed.length() > maxMessageLength) {
            trimmed = trimmed.substring(0, maxMessageLength);
            sendSystemMessage(userConnections.get(username.toLowerCase()), "Your message was too long and was truncated.");
            LOGGER.fine("Truncated long message from " + username);
        }

        String coloredUsername = UserColorManager.getColoredUsername(username);
        Message colored = new Message(
                Message.Type.CHAT,
                username,
                trimmed,
                coloredUsername,
                null,
                false
        );

        broadcastToAll(gson.toJson(colored));
        LOGGER.fine("CHAT " + username + ": " + trimmed);
    }

    private void handleWhisperPacket(WebSocket senderConn, String senderName, Message incoming) {
        String targetName = incoming.getTarget();
        if (targetName != null) {
            targetName = targetName.trim();
        }

        String cipherText = incoming.getContent();

        if (!isValidUsername(targetName)) {
            sendSystemMessage(senderConn, "Invalid whisper target. " + usernameRulesMessage());
            return;
        }

        if (cipherText == null || cipherText.isBlank()) {
            sendSystemMessage(senderConn, "Whisper message is empty.");
            return;
        }

        WebSocket targetConn = userConnections.get(targetName.toLowerCase());
        if (targetConn != null && targetConn.isOpen()) {
            Message toTarget = new Message(
                    Message.Type.WHISPER,
                    "[Whisper] " + senderName,
                    cipherText,
                    "§d[Whisper] " + senderName + "§r",
                    senderName,
                    true
            );

            targetConn.send(gson.toJson(toTarget));
            LOGGER.fine("WHISPER " + senderName + " -> " + targetName + " (" + cipherText.length() + " chars)");
        } else {
            sendSystemMessage(senderConn, "User '" + targetName + "' not found or not online.");
        }
    }

    // --- Broadcast helpers ---
    private static void broadcastToAll(String json) {
        for (WebSocket client : clients) {
            if (client.isOpen()) client.send(json);
        }
    }

    private static void broadcastToAllExcept(WebSocket exclude, String json) {
        for (WebSocket client : clients) {
            if (client != exclude && client.isOpen()) client.send(json);
        }
    }

    private void sendSystemMessage(WebSocket conn, String text) {
        if (conn == null) return;

        Message sys = new Message(
                Message.Type.SYSTEM,
                "[System]",
                text,
                "§e[System]§r",
                null,
                false
        );
        conn.send(gson.toJson(sys));
    }

    private void broadcastOnlineList() {
        String list = String.join(",", usernames.values());
        String content = "ONLINE_LIST:" + list;

        Message msg = new Message(
                Message.Type.SYSTEM,
                "[System]",
                content,
                "§e[System]§r",
                null,
                false
        );

        broadcastToAll(gson.toJson(msg));
        LOGGER.fine("Broadcasted ONLINE_LIST for " + usernames.size() + " users");
    }

    public static void shutdown() {
        LOGGER.info("Shutting down server and closing all connections...");
        for (WebSocket client : clients) {
            try {
                client.close(1000, "Server shutdown");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to close client connection cleanly", e);
            }
        }
        System.exit(0);
    }

    // --- Latest plugin cache (GitHub releases/latest) ---
    private static final class LatestPluginInfo {
        final String latestVersion;
        final String htmlUrl;
        final long fetchedAtMs;

        LatestPluginInfo(String latestVersion, String htmlUrl, long fetchedAtMs) {
            this.latestVersion = latestVersion;
            this.htmlUrl = htmlUrl;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private static final class LatestPluginInfoCache {
        private final String apiUrl;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();

        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "LatestPluginRefresh");
                    t.setDaemon(true);
                    return t;
                });

        private volatile LatestPluginInfo cached;
        private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

        LatestPluginInfoCache(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        LatestPluginInfo get() {
            return cached;
        }

        // Return cache immediately; refresh in background if stale/missing.
        LatestPluginInfo getFresh() {
            LatestPluginInfo info = cached;
            long now = System.currentTimeMillis();
            long staleTtlMs = TimeUnit.MINUTES.toMillis(STALE_TTL_MINUTES);

            boolean stale = (info == null) || (now - info.fetchedAtMs) > staleTtlMs;
            if (stale) {
                triggerRefreshAsync();
            }
            return info;
        }

        void startBackgroundRefresh() {
            scheduler.execute(this::refreshOnceSafe);

            scheduler.scheduleAtFixedRate(
                    this::refreshOnceSafe,
                    LATEST_REFRESH_HOURS,
                    LATEST_REFRESH_HOURS,
                    TimeUnit.HOURS
            );
        }

        private void triggerRefreshAsync() {
            if (!refreshInFlight.compareAndSet(false, true)) return;

            scheduler.execute(() -> {
                try {
                    refreshOnceSafe();
                } finally {
                    refreshInFlight.set(false);
                }
            });
        }

        private void refreshOnceSafe() {
            try {
                LatestPluginInfo info = fetchLatest();
                if (info != null && info.latestVersion != null && !info.latestVersion.isBlank()) {
                    LatestPluginInfo prev = cached;
                    cached = info;

                    String prevV = (prev != null ? prev.latestVersion : null);
                    if (prevV == null || !prevV.equals(info.latestVersion)) {
                        LOGGER.info("Latest plugin cached: " + info.latestVersion
                                + (info.htmlUrl != null ? " (" + info.htmlUrl + ")" : ""));
                    } else {
                        LOGGER.fine("Latest plugin unchanged: " + info.latestVersion);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to refresh latest plugin info (will retry)", e);
            }
        }

        private LatestPluginInfo fetchLatest() throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "RusherChatServer")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalStateException("GitHub API status " + res.statusCode() + ": " + res.body());
            }

            JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = obj.has("tag_name") ? obj.get("tag_name").getAsString() : null;
            String html = obj.has("html_url") ? obj.get("html_url").getAsString() : null;

            if (tag != null) tag = tag.trim();
            if (tag != null && (tag.startsWith("v") || tag.startsWith("V"))) tag = tag.substring(1);

            return new LatestPluginInfo(tag, html, System.currentTimeMillis());
        }
    }
}
