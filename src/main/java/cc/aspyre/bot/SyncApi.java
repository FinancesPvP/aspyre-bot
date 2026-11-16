package cc.aspyre.bot;

import cc.aspyre.bot.features.NewsFeature;
import cc.aspyre.bot.features.SyncFeature;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny web API your Minecraft plugin talks to. Every request needs the header
 * "Authorization: Bearer <sync_api.secret>".
 *
 * POST /sync         {"code":"123456","uuid":"...","ign":"Notch","rank":"VIP"}
 * POST /leaderboard  {"title":"Map 3","entries":[{"name":"Raiders","points":1200}]}
 * GET  /health
 */
public class SyncApi {
    private static final Logger log = LoggerFactory.getLogger(SyncApi.class);
    private final Bot bot;
    private final SyncFeature sync;
    private final NewsFeature news;

    public SyncApi(Bot bot, SyncFeature sync, NewsFeature news) {
        this.bot = bot;
        this.sync = sync;
        this.news = news;
    }

    public void start() throws IOException {
        Config.SyncApi c = bot.cfg.syncApi;
        HttpServer server = HttpServer.create(new InetSocketAddress(c.host, c.port), 0);
        server.createContext("/health", ex -> send(ex, 200, "{\"ok\":true}"));
        server.createContext("/sync", this::handleSync);
        server.createContext("/leaderboard", this::handleLeaderboard);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        log.info("Sync API listening on {}:{}", c.host, c.port);
    }

    private boolean authorized(HttpExchange ex) {
        String secret = bot.cfg.syncApi.secret;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return secret != null && !secret.isBlank() && ("Bearer " + secret).equals(header);
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private void handleSync(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"use POST\"}"); return; }
        if (!authorized(ex)) { send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        try {
            JsonObject body = readJson(ex);
            String code = body.get("code").getAsString();
            String uuid = body.get("uuid").getAsString();
            String ign = body.get("ign").getAsString();
            String rank = body.has("rank") && !body.get("rank").isJsonNull() ? body.get("rank").getAsString() : "";
            if (!code.matches("\\d{6}")) { send(ex, 400, "{\"error\":\"code must be 6 digits\"}"); return; }
            sync.addCode(code, uuid, ign, rank);
            send(ex, 200, "{\"ok\":true,\"expires_in\":300}");
        } catch (RuntimeException e) {
            send(ex, 400, "{\"error\":\"need code, uuid and ign\"}");
        }
    }

    private void handleLeaderboard(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"use POST\"}"); return; }
        if (!authorized(ex)) { send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        String title;
        try {
            JsonObject body = readJson(ex);
            title = body.has("title") ? body.get("title").getAsString() : "Faction";
            JsonArray arr = body.getAsJsonArray("entries");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                entries.add(Map.entry(o.get("name").getAsString(), o.get("points").getAsLong()));
            }
        } catch (RuntimeException e) {
            send(ex, 400, "{\"error\":\"need entries: [{name, points}]\"}");
            return;
        }
        boolean ok = news.updateLeaderboard(title, entries);
        send(ex, ok ? 200 : 500, "{\"ok\":" + ok + "}");
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
