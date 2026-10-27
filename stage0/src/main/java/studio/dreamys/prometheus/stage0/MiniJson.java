package studio.dreamys.prometheus.stage0;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a GitHub release: objects become {@code Map}, arrays {@code List}, numbers {@code Double},
 * plus {@code String}, {@code Boolean} and {@code null}. Stage 0 can't count on the game's Gson being visible from
 * every hook's class loader, so it doesn't use it.
 */
final class MiniJson {
    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.at != text.length()) throw parser.error("trailing characters");
        return value;
    }

    private Object value() {
        skipWhitespace();
        if (at >= text.length()) throw error("unexpected end");
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++; // {
        skipWhitespace();
        if (peek('}')) return map;
        while (true) {
            skipWhitespace();
            // look, don't consume: string() skips the opening quote itself
            if (at >= text.length() || text.charAt(at) != '"') throw error("expected a key");
            String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value());
            skipWhitespace();
            if (peek('}')) return map;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++; // [
        skipWhitespace();
        if (peek(']')) return list;
        while (true) {
            list.add(value());
            skipWhitespace();
            if (peek(']')) return list;
            expect(',');
        }
    }

    private String string() {
        at++; // "
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) break;
            char escaped = text.charAt(at++);
            switch (escaped) {
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (at + 4 > text.length()) throw error("bad unicode escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    out.append(escaped); // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("expected " + word);
        at += word.length();
        return value;
    }

    /**
     * Consumes {@code c} if it is next.
     */
    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private void skipWhitespace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("bad JSON at " + at + ": " + what);
    }
}
