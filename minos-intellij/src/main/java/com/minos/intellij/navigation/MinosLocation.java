package com.minos.intellij.navigation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

public record MinosLocation(
        String fileId,
        int startLine,
        int startColumn,
        String positionEncoding
) {
    public static MinosLocation from(JsonObject location) {
        if (location == null) {
            throw new IllegalArgumentException("location must not be null");
        }
        return new MinosLocation(
                requiredString(location, "fileId"),
                location.get("startLine").getAsInt(),
                location.get("startColumn").getAsInt(),
                optionalString(location, "positionEncoding", "UNKNOWN")
        );
    }

    public int utf16Column(CharSequence line) {
        int requested = Math.max(0, startColumn);
        return switch (positionEncoding) {
            case "UTF16_CODE_UNITS" -> Math.min(requested, line.length());
            case "UTF32_CODE_UNITS" -> utf32ToUtf16(line, requested);
            case "UTF8_CODE_UNITS" -> utf8ToUtf16(line, requested);
            default -> Math.min(requested, line.length());
        };
    }

    private static int utf32ToUtf16(CharSequence text, int codePointColumn) {
        String value = text.toString();
        int count = value.codePointCount(0, value.length());
        return value.offsetByCodePoints(0, Math.min(codePointColumn, count));
    }

    private static int utf8ToUtf16(CharSequence text, int byteColumn) {
        String value = text.toString();
        int utf16Index = 0;
        int consumedBytes = 0;
        while (utf16Index < value.length() && consumedBytes < byteColumn) {
            int codePoint = value.codePointAt(utf16Index);
            String scalar = new String(Character.toChars(codePoint));
            int bytes = scalar.getBytes(StandardCharsets.UTF_8).length;
            if (consumedBytes + bytes > byteColumn) {
                break;
            }
            consumedBytes += bytes;
            utf16Index += Character.charCount(codePoint);
        }
        return utf16Index;
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || element.getAsString().isBlank()) {
            throw new IllegalArgumentException("location is missing `" + name + "`");
        }
        return element.getAsString();
    }

    private static String optionalString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }
}
