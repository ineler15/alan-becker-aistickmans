package com.stickmanai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the flat command/status objects exchanged
 * with the Node "brain" process. Not a general-purpose JSON library -
 * just enough to round-trip strings, numbers, booleans, objects and arrays.
 */
public class Json {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object result = p.parseValue();
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        return new LinkedHashMap<String, Object>();
    }

    public static String write(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        writeValue(map, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                writeString(e.getKey(), sb);
                sb.append(":");
                writeValue(e.getValue(), sb);
            }
            sb.append("}");
        } else if (value instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(",");
                first = false;
                writeValue(item, sb);
            }
            sb.append("]");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static class Parser {
        private final String text;
        private int pos = 0;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == '{') return parseObjectValue();
            if (c == '[') return parseArrayValue();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        Map<String, Object> parseObjectValue() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++; // :
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
            }
            return map;
        }

        List<Object> parseArrayValue() {
            List<Object> list = new ArrayList<Object>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    break;
                }
            }
            return list;
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (text.charAt(pos) != '"') {
                char c = text.charAt(pos);
                if (c == '\\') {
                    pos++;
                    char esc = text.charAt(pos);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'u':
                            String hex = text.substring(pos + 1, pos + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                pos++;
            }
            pos++; // closing quote
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            pos += 5;
            return Boolean.FALSE;
        }

        Double parseNumber() {
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(text.substring(start, pos));
        }

        char peek() {
            return text.charAt(pos);
        }
    }
}
