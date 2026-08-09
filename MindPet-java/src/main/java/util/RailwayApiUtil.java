package util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared 12306 railway API utilities.
 * Used by both BookTicketTool and future SnapTicketTool.
 * —— Cookie-based authentication, station code mapping, ordering flow.
 */
public final class RailwayApiUtil {

    private static final String ACCOUNT_FILE = "12306_account.txt";
    private static final String STATION_JS_URL = "https://kyfw.12306.cn/otn/resources/js/framework/station_name.js";
    private static final String QUERY_URL = "https://kyfw.12306.cn/otn/leftTicket/query";
    private static final String SUBMIT_URL = "https://kyfw.12306.cn/otn/leftTicket/submitOrderRequest";
    private static final String INIT_DC_URL = "https://kyfw.12306.cn/otn/confirmPassenger/initDc";
    private static final String CHECK_ORDER_URL = "https://kyfw.12306.cn/otn/confirmPassenger/checkOrderInfo";
    private static final String QUEUE_COUNT_URL = "https://kyfw.12306.cn/otn/confirmPassenger/getQueueCount";
    private static final String CONFIRM_QUEUE_URL = "https://kyfw.12306.cn/otn/confirmPassenger/confirmSingleForQueue";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Map<String, String> stationCodeCache = null;

    private RailwayApiUtil() {}

    // ==================== Account Management ====================

    public static RailwayAccount loadAccount() throws IOException {
        Path path = Path.of(ACCOUNT_FILE);
        if (!Files.exists(path)) return null;

        Properties props = new Properties();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        String cookie = props.getProperty("cookie");
        String name = props.getProperty("passenger_name");
        String id = props.getProperty("passenger_id");
        String phone = props.getProperty("passenger_phone");
        if (cookie == null || cookie.isEmpty()) return null;

        return new RailwayAccount(cookie, name, id, phone);
    }

    public static void saveAccount(String rawText) throws IOException {
        // Parse multiline text: key=value pairs, key: value, or key value
        Properties props = new Properties();
        for (String line : rawText.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("/")) continue;
            // Try key=value
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                continue;
            }
            // Try key: value
            int colon = line.indexOf(':');
            if (colon > 0) {
                props.setProperty(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(ACCOUNT_FILE), StandardCharsets.UTF_8)) {
            props.store(w, "12306 Account — DO NOT COMMIT");
        }
    }

    public static boolean validateCookie(String cookie) {
        try {
            HttpURLConnection conn = getConn("https://kyfw.12306.cn/otn/view/personal_consign.html", cookie);
            conn.setInstanceFollowRedirects(false);
            int status = conn.getResponseCode();
            return status == 200; // 302 to login page means cookie expired
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Station Code Mapping ====================

    public static synchronized String getStationCode(String name) throws Exception {
        if (stationCodeCache == null) loadStations();
        if (stationCodeCache == null) return name; // fallback
        String code = stationCodeCache.get(name);
        if (code != null) return code;
        // Fuzzy: try matching "杭州" → "杭州东"
        for (Map.Entry<String, String> e : stationCodeCache.entrySet()) {
            if (e.getKey().startsWith(name) || name.startsWith(e.getKey())) return e.getValue();
        }
        return name;
    }

    private static void loadStations() throws Exception {
        HttpURLConnection conn = getConn(STATION_JS_URL, null);
        String js = readBody(conn);
        // Format: @bjb|北京北|VAP|beijingbei|bjb|...
        Map<String, String> map = new ConcurrentHashMap<>();
        Matcher m = Pattern.compile("@[^|]+\\|([^|]+)\\|([^|]+)\\|").matcher(js);
        while (m.find()) map.put(m.group(1), m.group(2));
        if (!map.isEmpty()) stationCodeCache = map;
    }

    // ==================== Ticket Search ====================

    /**
     * Search 12306 for a specific train and return its secretStr.
     * Returns null if train not found or no tickets.
     */
    public static String findTrainSecret(String from, String to, String date,
                                          String trainNo, String seatType, String cookie) throws Exception {
        String fromCode = getStationCode(from);
        String toCode = getStationCode(to);
        String url = QUERY_URL + "?leftTicketDTO.train_date=" + date
            + "&leftTicketDTO.from_station=" + fromCode
            + "&leftTicketDTO.to_station=" + toCode
            + "&purpose_codes=ADULT";

        HttpURLConnection conn = getConn(url, cookie);
        JsonNode root = MAPPER.readTree(readBody(conn));
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) return null;

        for (JsonNode train : result) {
            String raw = train.asText();
            String[] fields = raw.split("\\|");
            if (fields.length < 36) continue;
            // field[3] = station_train_code (e.g. "G144")
            if (!trainNo.equals(fields[3])) continue;
            // Check seat availability
            if (!hasSeat(fields, seatType)) return null; // no tickets for this seat

            // Build secretStr — 12306 encodes it in a specific way
            return fields[0]; // secretStr is field[0] for query result
        }
        return null;
    }

    /**
     * Fuzzy match: find any train matching type + time window + seat.
     * Returns train info String[] {secretStr, trainCode, startTime} or null.
     */
    public static String[] findMatchingTrain(String from, String to, String date,
                                              String trainType, String timeWindow,
                                              String seatType, String cookie) throws Exception {
        String fromCode = getStationCode(from);
        String toCode = getStationCode(to);
        String url = QUERY_URL + "?leftTicketDTO.train_date=" + date
            + "&leftTicketDTO.from_station=" + fromCode
            + "&leftTicketDTO.to_station=" + toCode
            + "&purpose_codes=ADULT";

        HttpURLConnection conn = getConn(url, cookie);
        JsonNode root = MAPPER.readTree(readBody(conn));
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) return null;

        // Parse time window
        String[] window = timeWindow != null ? timeWindow.split("-") : new String[]{"00:00", "23:59"};
        int startMin = toMinutes(window[0].trim());
        int endMin = toMinutes(window[1].trim());

        for (JsonNode train : result) {
            String raw = train.asText();
            String[] f = raw.split("\\|");
            if (f.length < 36) continue;
            String code = f[3]; // station_train_code

            // Filter by train type
            if (trainType != null && !trainType.isEmpty() && !"不限".equals(trainType)) {
                if (!code.startsWith(trainType)) continue;
            }

            // Filter by time window
            String depTime = f[8]; // start_time
            int depMin = toMinutes(depTime);
            if (depMin < startMin || depMin > endMin) continue;

            // Check seat
            if (!hasSeat(f, seatType)) continue;

            return new String[]{f[0], code, depTime}; // secretStr, train_code, start_time
        }
        return null;
    }

    private static int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static boolean hasSeat(String[] fields, String seatType) {
        // 12306 seat field mapping (in query result pipe format):
        // field indices vary by response version, typical:
        // 二等座: index around 30, 一等座: around 31, 商务座: around 32
        // Values: "" = no ticket, number = remaining, "有" = available
        String seat = switch (seatType) {
            case "二等座" -> fields.length > 30 ? fields[30] : "";
            case "一等座" -> fields.length > 31 ? fields[31] : "";
            case "商务座" -> fields.length > 32 ? fields[32] : "";
            case "软卧" -> fields.length > 23 ? fields[23] : "";
            case "硬卧" -> fields.length > 28 ? fields[28] : "";
            case "硬座" -> fields.length > 29 ? fields[29] : "";
            case "无座" -> fields.length > 26 ? fields[26] : "";
            default -> "";
        };
        return seat != null && !seat.isEmpty() && !"--".equals(seat) && !"无".equals(seat);
    }

    // ==================== Order Flow ====================

    /**
     * Full ordering flow. Returns success/failure message.
     */
    public static String submitOrder(String from, String to, String date,
                                      String trainNo, String seatType, RailwayAccount account) {
        try {
            // Step 0: Find train and get secretStr
            String secretStr = findTrainSecret(from, to, date, trainNo, seatType, account.cookie);
            if (secretStr == null) return "未找到 " + trainNo + " 的 " + seatType + " 余票";

            // Step 1: Submit order request (enter queue)
            String fromCode = getStationCode(from);
            String toCode = getStationCode(to);
            String body1 = "secretStr=" + URLEncoder.encode(secretStr, "UTF-8")
                + "&train_date=" + date
                + "&query_from_station_name=" + URLEncoder.encode(from, "UTF-8")
                + "&query_to_station_name=" + URLEncoder.encode(to, "UTF-8")
                + "&purpose_codes=ADULT";
            JsonNode r1 = postJson(SUBMIT_URL, body1, account.cookie);
            if (!r1.path("status").asBoolean() && !r1.path("data").path("result").isArray()) {
                String msg = r1.path("messages").get(0).asText(null);
                return "下单申请失败: " + (msg != null ? msg : "未知错误");
            }

            // Step 2: Init passenger info
            String body2 = "_json_att=";
            JsonNode r2 = postJson(INIT_DC_URL, body2, account.cookie);
            if (!r2.path("status").asBoolean()) {
                return "获取乘客信息失败，cookie 可能已过期";
            }

            // Step 3: Check order info
            String passengerTicketStr = seatTypeCode(seatType) + "_0_"
                + (account.name != null ? account.name : "") + "_1_"
                + (account.id != null ? account.id : "") + "_"
                + (account.phone != null ? account.phone : "") + "_N";
            String oldPassengerStr = (account.name != null ? account.name : "") + "_1_" + (account.id != null ? account.id : "");

            String body3 = "cancel_flag=2&bed_level_order_num=000000000000000000000000000000"
                + "&passengerTicketStr=" + URLEncoder.encode(passengerTicketStr, "UTF-8")
                + "&oldPassengerStr=" + URLEncoder.encode(oldPassengerStr, "UTF-8")
                + "&tour_flag=1&randCode=&whatsSelect=1&_json_att=&"
                + "REPEAT_SUBMIT_TOKEN=" + r2.path("data").path("repeatSubmitToken").asText("");

            JsonNode r3 = postJson(CHECK_ORDER_URL, body3, account.cookie);
            if (!r3.path("status").asBoolean()) {
                return "订单校验失败，请检查乘客信息";
            }

            // Step 4: Get queue count
            String body4 = "train_date=" + date + "&train_no="
                + r3.path("data").path("train_no").asText("")
                + "&stationTrainCode=" + trainNo
                + "&seatType=" + seatTypeCode(seatType)
                + "&fromStationTelecode=" + fromCode
                + "&toStationTelecode=" + toCode
                + "&leftTicket=" + URLEncoder.encode(secretStr, "UTF-8")
                + "&purpose_codes=ADULT&_json_att=";
            JsonNode r4 = postJson(QUEUE_COUNT_URL, body4, account.cookie);

            // Step 5: Confirm and enter queue
            String body5 = "passengerTicketStr=" + URLEncoder.encode(passengerTicketStr, "UTF-8")
                + "&oldPassengerStr=" + URLEncoder.encode(oldPassengerStr, "UTF-8")
                + "&randCode=&purpose_codes=ADULT"
                + "&key_check_isChange=" + r3.path("data").path("ifShowPassCode").asText("")
                + "&leftTicketStr=" + URLEncoder.encode(secretStr, "UTF-8")
                + "&train_location=" + r3.path("data").path("train_location").asText("")
                + "&choose_seats=&seatDetailType=000&whatsSelect=1&roomType=00"
                + "&dwAll=N&_json_att=&REPEAT_SUBMIT_TOKEN="
                + r3.path("data").path("repeatSubmitToken").asText("");

            JsonNode r5 = postJson(CONFIRM_QUEUE_URL, body5, account.cookie);
            if (r5.path("status").asBoolean() && r5.path("data").path("submitStatus").asBoolean()) {
                return "SUCCESS: 已成功下单！" + trainNo + " " + from + "→" + to
                    + " " + date + " " + seatType + "\n请30分钟内登录12306支付";
            }
            return "下单失败: " + r5.path("data").path("errMsg").asText("请稍后重试");

        } catch (Exception e) {
            return "下单异常: " + e.getMessage();
        }
    }

    // ==================== HTTP Helpers ====================

    private static HttpURLConnection getConn(String urlStr, String cookie) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static JsonNode postJson(String urlStr, String body, String cookie) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new RuntimeException("HTTP " + status);
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return MAPPER.readTree(sb.toString());
        }
    }

    private static String seatTypeCode(String name) {
        return switch (name) {
            case "商务座" -> "9";
            case "特等座" -> "P";
            case "一等座" -> "M";
            case "二等座" -> "O";
            case "软卧" -> "4";
            case "硬卧" -> "3";
            case "硬座" -> "1";
            case "无座" -> "1";
            default -> "O"; // default 二等座
        };
    }

    // ==================== Account POJO ====================

    public record RailwayAccount(String cookie, String name, String id, String phone) {}
}
