package com.example.txn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec so the backend has no third-party runtime dependency. */
public final class Json {
    private Json() { }

    public static Object parse(String source) {
        Parser parser = new Parser(source);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index != source.length()) {
            throw parser.error("Unexpected content after JSON value");
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(value, output);
        return output.toString();
    }

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new ValidationException("JSON body must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void writeValue(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            writeString(string, output);
        } else if (value instanceof BigDecimal decimal) {
            output.append(decimal.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), output);
                output.append(':');
                writeValue(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeValue(item, output);
            }
            output.append(']');
        } else {
            writeString(value.toString(), output);
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 32) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            if (source == null) {
                throw new ValidationException("JSON body is required");
            }
            this.source = source;
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= source.length()) {
                throw error("Expected JSON value");
            }
            char next = source.charAt(index);
            return switch (next) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            index++; // opening brace
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != '"') {
                    throw error("Object key must be a string");
                }
                String key = readString();
                if (result.containsKey(key)) {
                    throw error("Duplicate object key: " + key);
                }
                skipWhitespace();
                require(':');
                result.put(key, readValue());
                skipWhitespace();
                if (take('}')) {
                    return result;
                }
                require(',');
            }
        }

        private List<Object> readArray() {
            index++; // opening bracket
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) {
                    return result;
                }
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (index >= source.length()) {
                        throw error("Incomplete string escape");
                    }
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicodeEscape());
                        default -> throw error("Invalid string escape");
                    }
                } else {
                    if (character < 32) {
                        throw error("Control character in string");
                    }
                    result.append(character);
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw error("Incomplete unicode escape");
            }
            try {
                char result = (char) Integer.parseInt(source.substring(index, index + 4), 16);
                index += 4;
                return result;
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private BigDecimal readNumber() {
            int start = index;
            take('-');
            if (!take('0')) {
                readDigits();
            }
            if (take('.')) {
                readDigits();
            }
            if (take('e') || take('E')) {
                if (!take('+')) {
                    take('-');
                }
                readDigits();
            }
            try {
                return new BigDecimal(source.substring(start, index));
            } catch (NumberFormatException exception) {
                throw error("Invalid JSON number");
            }
        }

        private void readDigits() {
            int start = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("Expected digit");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!source.startsWith(literal, index)) {
                throw error("Invalid JSON literal");
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            skipWhitespace();
            if (!take(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private ValidationException error(String message) {
            return new ValidationException(message + " at character " + index);
        }
    }
}
