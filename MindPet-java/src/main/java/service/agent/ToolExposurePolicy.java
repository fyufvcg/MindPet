package service.agent;

import java.util.Locale;
import java.util.Set;

/** One exposure rule for routing, discovery, catalogs and legacy MCP connections. */
public final class ToolExposurePolicy {
    private ToolExposurePolicy() {}

    private static final Set<String> JAVA_TOOLS = Set.of(
        "numericCalculate", "dateCalculate", "convertTimezone",
        "listRecentMails", "searchMails", "getMailDetail", "sendMail", "replyMail", "deleteMail");

    private static final Set<String> RETIRED_TOOLS = Set.of(
        "getWeather", "subscribeWeather", "unsubscribeWeather", "showSubscription", "sunSkySpots",
        "changeVoice", "speakText", "translateText", "convertCurrency", "ipGeolocation", "routePlanning",
        "reimburseInvoice", "reverifyInvoice", "queryReimbursements",
        "chart_bar", "chart_line", "chart_pie", "chart_scatter",
        "get_location", "list_homes", "list_devices", "list_scenes", "list_consumables",
        "get_device_spec", "get_device_properties", "set_device_property", "run_device_action",
        "run_scene", "run_speaker_command");

    public static boolean allows(String name) {
        if (name == null || name.isBlank()) return false;
        if (!name.contains("__")) return JAVA_TOOLS.contains(name);
        String raw = name.substring(name.lastIndexOf("__") + 2);
        return !RETIRED_TOOLS.contains(raw) && !raw.startsWith("mcdonald")
            && !raw.startsWith("didi_") && !raw.startsWith("anime_")
            && !retiredDomain(raw) && !retiredDomain(name);
    }

    /** Exclude old vertical MCP servers, including their generic login/status helpers. */
    public static boolean retiredDomain(String identity) {
        if (identity == null) return false;
        String value = identity.toLowerCase(Locale.ROOT);
        return value.matches("(?s).*(?:\\b|_|/|-)" +
            "(?:mijia|xiaomi|mi-home|mihome|weather|anime|didi|mcdonald|mcdonalds|quickchart)(?:\\b|_|/|-).*")
            || value.contains("米家") || value.contains("麦当劳") || value.contains("天气")
            || value.contains("滴滴") || value.contains("动漫");
    }
}
