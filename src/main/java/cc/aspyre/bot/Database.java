package cc.aspyre.bot;

import java.sql.*;
import java.util.*;

/**
 * Small SQLite wrapper. Everything the bot remembers lives in data.db next to the jar.
 * Rows come back as maps of column name -> value; use {@link #num(Map, String)} for numbers.
 */
public class Database {
    private final Connection conn;

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)",
        "CREATE TABLE IF NOT EXISTS sync_codes (code TEXT PRIMARY KEY, uuid TEXT, ign TEXT, rank TEXT, expires_at INTEGER)",
        "CREATE TABLE IF NOT EXISTS links (discord_id INTEGER PRIMARY KEY, uuid TEXT UNIQUE, ign TEXT, rank TEXT, linked_at INTEGER)",
        "CREATE TABLE IF NOT EXISTS giveaways (message_id INTEGER PRIMARY KEY, channel_id INTEGER, prize TEXT, winners INTEGER, "
            + "ends_at INTEGER, host_id INTEGER, requirements TEXT, ended INTEGER DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS giveaway_entries (message_id INTEGER, user_id INTEGER, PRIMARY KEY (message_id, user_id))",
        "CREATE TABLE IF NOT EXISTS polls (message_id INTEGER PRIMARY KEY, channel_id INTEGER, question TEXT, options TEXT, "
            + "ends_at INTEGER, ended INTEGER DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS strikes (id INTEGER PRIMARY KEY AUTOINCREMENT, faction TEXT, reason TEXT, points INTEGER, "
            + "staff_id INTEGER, created_at INTEGER, active INTEGER DEFAULT 1)",
        "CREATE TABLE IF NOT EXISTS tickets (channel_id INTEGER PRIMARY KEY, user_id INTEGER, type TEXT, opened_at INTEGER, closed INTEGER DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS bugs (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER, channel_id INTEGER, user_id INTEGER, "
            + "location TEXT, description TEXT, steps TEXT, status TEXT DEFAULT 'open')",
        "CREATE TABLE IF NOT EXISTS suggestions (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER, channel_id INTEGER, "
            + "user_id INTEGER, text TEXT, status TEXT DEFAULT 'pending', note TEXT)",
        "CREATE TABLE IF NOT EXISTS recruit_posts (user_id INTEGER PRIMARY KEY, last_post INTEGER)"
    };

    public Database(String path) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = conn.createStatement()) {
            for (String sql : SCHEMA) st.execute(sql);
        }
    }

    /** Runs an INSERT/UPDATE/DELETE. Returns the new row id for inserts, otherwise the number of rows changed. */
    public synchronized long update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            int changed = ps.executeUpdate();
            if (sql.trim().toUpperCase().startsWith("INSERT")) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
            return changed;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized List<Map<String, Object>> all(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> one(String sql, Object... params) {
        List<Map<String, Object>> rows = all(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    public static long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    // key/value store (ids of the leaderboard message, etc.)
    public String get(String key) {
        Map<String, Object> row = one("SELECT value FROM kv WHERE key = ?", key);
        return row == null ? null : str(row, "value");
    }

    public void set(String key, String value) {
        update("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
    }
}
