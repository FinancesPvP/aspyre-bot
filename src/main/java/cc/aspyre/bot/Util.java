package cc.aspyre.bot;

import java.util.List;
import java.util.Map;

public final class Util {
    private Util() {}

    public static final List<String> LETTERS = List.of("🇦", "🇧", "🇨", "🇩", "🇪");

    public static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    /** Discord timestamp that shows in each reader's own timezone. Styles: F full, R relative, d/D date. */
    public static String ts(long unixSec, char style) {
        return "<t:" + unixSec + ":" + style + ">";
    }

    private static final Map<Character, Long> UNITS = Map.of('s', 1L, 'm', 60L, 'h', 3600L, 'd', 86400L, 'w', 604800L);

    /** "30m", "2h", "1d", "1h30m" -> seconds. A bare number means minutes. Returns -1 if unreadable. */
    public static long parseDuration(String text) {
        long total = 0;
        StringBuilder num = new StringBuilder();
        for (char c : text.trim().toLowerCase().replace(" ", "").toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (UNITS.containsKey(c) && num.length() > 0) {
                total += Long.parseLong(num.toString()) * UNITS.get(c);
                num.setLength(0);
            } else {
                return -1;
            }
        }
        if (num.length() > 0) total += Long.parseLong(num.toString()) * 60;
        return total > 0 ? total : -1;
    }

    public static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
