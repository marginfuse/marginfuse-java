package com.marginfuse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader and writer for exactly this SDK's traffic.
 *
 * <p>It exists so the package has no dependencies. That matters more in Java
 * than elsewhere: a library that drags in Jackson or Gson forces its version on
 * every application that embeds it, and version conflicts between transitive
 * copies are a genuine and familiar pain. An SDK that sits inside somebody
 * else's build should not start an argument with it.
 *
 * <p>It is not a general purpose parser and does not try to be. It reads the
 * responses this API produces and writes the requests this SDK sends. Anything
 * beyond that is out of scope by design, so the surface stays small enough to
 * be worth trusting.
 */
final class Json {

    private Json() {}

    // ------------------------------------------------------------- writing

    /** Appends a JSON value. Maps, lists, strings, numbers, booleans, null. */
    static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (e.getValue() == null) continue; // absent, not null: the API rejects unknown shapes
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialise " + value.getClass());
        }
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------- reading

    /** Parses a JSON document. Throws on anything malformed. */
    static Object read(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (!p.done()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return value;
    }

    /** Reads a document expected to be an object, or throws. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readObject(String text) {
        Object value = read(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String string(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String ? (String) v : null;
    }

    static boolean bool(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) { this.src = src; }

        boolean done() { return pos >= src.length(); }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value() {
            if (done()) throw new IllegalArgumentException("unexpected end of input");
            char c = src.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default: return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return out; }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                out.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') return out;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + pos);
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return out; }
            while (true) {
                skipWhitespace();
                out.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') return out;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + pos);
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char esc = next();
                switch (esc) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + esc);
                }
            }
        }

        Object literal(String word, Object value) {
            if (!src.startsWith(word, pos)) {
                throw new IllegalArgumentException("bad literal at " + pos);
            }
            pos += word.length();
            return value;
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!done()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String raw = src.substring(start, pos);
            if (raw.isEmpty()) throw new IllegalArgumentException("expected a value at " + start);
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // falls through to double for values beyond long
                }
            }
            return Double.valueOf(raw);
        }

        char peek() {
            if (done()) throw new IllegalArgumentException("unexpected end of input");
            return src.charAt(pos);
        }

        char next() {
            if (done()) throw new IllegalArgumentException("unexpected end of input");
            return src.charAt(pos++);
        }

        void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("expected " + c + " at " + (pos - 1));
        }
    }
}
