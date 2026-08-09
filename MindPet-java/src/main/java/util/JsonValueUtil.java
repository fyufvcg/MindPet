package util;

public final class JsonValueUtil {

    private JsonValueUtil() {
    }

    public static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int colonIndex = json.indexOf(":", keyIndex + searchKey.length());
        if (colonIndex == -1) return null;

        int pos = colonIndex + 1;
        while (pos < json.length() && json.charAt(pos) == ' ') pos++;
        if (pos >= json.length()) return null;

        if (json.charAt(pos) == '"') {
            int end = json.indexOf('"', pos + 1);
            if (end == -1) return null;
            return json.substring(pos + 1, end);
        }

        int end = pos;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
            end++;
        }
        return json.substring(pos, end).trim();
    }
}
