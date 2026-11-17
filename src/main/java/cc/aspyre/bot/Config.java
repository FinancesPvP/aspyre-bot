package cc.aspyre.bot;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Mirrors config.json. JSON keys are snake_case (guild_id, max_cps, ...). */
public class Config {
    public String token;
    public long guildId;

    public String serverIp = "play.yourserver.net";
    public String storeLink = "";
    public String websiteLink = "";
    public int maxCps = 16;
    public int factionSize = 10;
    public int allyLimit = 0;
    public int maxStrikes = 3;
    public String boosterPerk = "";
    public String embedColor = "#4CADD0";
    public String twitterLink = "";
    /** Name of the server's custom emoji used at the end of the welcome message. */
    public String welcomeEmoji = "parapvp";
    /** Rotating "Playing ..." status. {members} becomes the member count. */
    public java.util.List<String> presences = new java.util.ArrayList<>(java.util.List.of("parapvp.com", "with {members} members"));
    /** Name -> role ID for the alert/notification roles select menu (/roles-panel). */
    public Map<String, Long> notificationRoles = new java.util.LinkedHashMap<>();

    public Map<String, Long> channels = new HashMap<>();
    public Map<String, Long> ticketCategories = new HashMap<>();
    public Map<String, Long> roles = new HashMap<>();
    public Map<String, Long> rankRoles = new HashMap<>();
    public SyncApi syncApi = new SyncApi();

    public static class SyncApi {
        public boolean enabled = true;
        public String host = "0.0.0.0";
        public int port = 8095;
        public String secret = "";
    }

    /** Channels with a built-in default, so they work even if config.json doesn't list them. */
    private static final Map<String, Long> DEFAULT_CHANNELS = Map.of("changelog", 1571728111131951114L);

    public long channel(String key) {
        Long id = channels.get(key);
        return id != null && id != 0 ? id : DEFAULT_CHANNELS.getOrDefault(key, 0L);
    }

    public long role(String key) {
        return roles.getOrDefault(key, 0L);
    }

    public static Config load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("config.json not found. Copy config.example.json to config.json and fill it in.");
        }
        Gson gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return gson.fromJson(r, Config.class);
        }
    }
}
