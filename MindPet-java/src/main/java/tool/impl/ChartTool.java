package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tool.ToolUserContext;
import util.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the remote QuickChart HTTP API to create PNG charts. */
@Component
public class ChartTool {

    private static final int MAX_INPUT_CHARS = 200_000;
    private static final int MAX_POINTS = 1_000;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> COLORS = List.of(
        "#3B82F6", "#14B8A6", "#F59E0B", "#EF4444", "#8B5CF6",
        "#06B6D4", "#84CC16", "#F97316", "#EC4899", "#64748B");

    private final Logger logger;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String chartApiUrl;

    public ChartTool(Logger logger,
                     @Value("${app.chart.api-url:https://quickchart.io/chart}") String chartApiUrl) {
        this.logger = logger;
        this.chartApiUrl = chartApiUrl;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Tool(description = "分析分类数据并生成柱状图 PNG。用户要求展示、发送或导出柱状图时必须调用。data 为 JSON，推荐格式：{\"series\":[{\"name\":\"系列名\",\"x\":[\"A\",\"B\"],\"y\":[1,2]}]}")
    public String chart_bar(
            @ToolParam(description = "待分析并绘图的 JSON 数据") String data,
            @ToolParam(description = "准确概括数据含义的图表标题") String title) {
        return generate("bar", data, title);
    }

    @Tool(description = "分析趋势或时间序列数据并生成折线图 PNG。用户要求展示、发送或导出折线图时必须调用。data 格式同 chart_bar")
    public String chart_line(
            @ToolParam(description = "待分析并绘图的 JSON 数据") String data,
            @ToolParam(description = "准确概括数据含义的图表标题") String title) {
        return generate("line", data, title);
    }

    @Tool(description = "分析占比数据并生成饼图 PNG。用户要求展示、发送或导出饼图时必须调用。data 格式：{\"series\":[{\"name\":\"占比\",\"x\":[\"类别A\",\"类别B\"],\"y\":[30,70]}]}")
    public String chart_pie(
            @ToolParam(description = "待分析并绘图的 JSON 数据") String data,
            @ToolParam(description = "准确概括数据含义的图表标题") String title) {
        return generate("pie", data, title);
    }

    @Tool(description = "分析两个数值变量的关系并生成散点图 PNG。用户要求展示、发送或导出散点图时必须调用。data 格式同 chart_bar，其中 x 和 y 都应为数值数组")
    public String chart_scatter(
            @ToolParam(description = "待分析并绘图的 JSON 数据") String data,
            @ToolParam(description = "准确概括数据含义的图表标题") String title) {
        return generate("scatter", data, title);
    }

    private String generate(String type, String data, String title) {
        try {
            if (data == null || data.isBlank()) return "图表生成失败：数据不能为空。";
            if (data.length() > MAX_INPUT_CHARS) return "图表生成失败：数据量过大，请先聚合或筛选到关键数据。";

            Map<String, Object> chart = buildChartConfig(type, data, title);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chart", chart);
            payload.put("width", 1000);
            payload.put("height", 600);
            payload.put("devicePixelRatio", 2);
            payload.put("format", "png");
            payload.put("backgroundColor", "white");
            payload.put("version", "4");

            HttpRequest request = HttpRequest.newBuilder(URI.create(chartApiUrl))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Accept", "image/png")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] png = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String error = new String(png, StandardCharsets.UTF_8);
                return "图表生成失败：远程图表服务返回 HTTP " + response.statusCode() + "，"
                    + abbreviate(error, 300);
            }
            if (!isPng(png)) return "图表生成失败：远程服务没有返回有效的 PNG 图片。";
            if (png.length > MAX_IMAGE_BYTES) return "图表生成失败：返回的图片超过 12MB 限制。";

            Path output = createOutputPath(title);
            Files.write(output, png);
            String name = output.getFileName().toString();
            String url = toLocalFileUrl(output);
            ToolUserContext.addGeneratedFile(output.toString(), name, "image/png", url);
            logger.log("INFO", "远程图表已生成: " + output + " (" + png.length + " bytes)");

            return "图表已生成并登记为可发送的 PNG 附件。\n"
                + "文件名: " + name + "\n"
                + "文件路径: " + output + "\n"
                + "请在最终回复中保留下面的图片引用：\n"
                + "![" + markdownLabel(title) + "](" + url + ")";
        } catch (IllegalArgumentException e) {
            return "图表生成失败：" + e.getMessage();
        } catch (Exception e) {
            logger.log("ERROR", "远程图表生成异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return "图表生成失败：远程服务暂时不可用，请稍后重试。";
        }
    }

    private Map<String, Object> buildChartConfig(String type, String data, String title) throws Exception {
        JsonNode root = mapper.readTree(data);
        if (root != null && root.isTextual()) root = mapper.readTree(root.asText());
        List<Series> series = parseSeries(root);
        if (series.isEmpty()) throw new IllegalArgumentException("没有找到可绘制的数值数据");

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", type);
        Map<String, Object> chartData = new LinkedHashMap<>();
        List<Map<String, Object>> datasets = new ArrayList<>();

        if ("pie".equals(type)) {
            Series first = series.get(0);
            chartData.put("labels", labelsFor(first));
            Map<String, Object> dataset = new LinkedHashMap<>();
            dataset.put("label", first.name());
            dataset.put("data", first.y());
            dataset.put("backgroundColor", colors(first.y().size(), 0, 0.78));
            dataset.put("borderColor", "#FFFFFF");
            dataset.put("borderWidth", 2);
            datasets.add(dataset);
        } else if ("scatter".equals(type)) {
            for (int index = 0; index < series.size(); index++) {
                Series item = series.get(index);
                if (item.x().size() != item.y().size()) {
                    throw new IllegalArgumentException("散点图的 x 与 y 数据数量必须一致");
                }
                List<Map<String, Object>> points = new ArrayList<>();
                for (int i = 0; i < item.y().size(); i++) {
                    Object x = item.x().get(i);
                    if (!(x instanceof Number) || !(item.y().get(i) instanceof Number)) {
                        throw new IllegalArgumentException("散点图的 x 与 y 必须都是数值");
                    }
                    points.add(Map.of("x", x, "y", item.y().get(i)));
                }
                Map<String, Object> dataset = new LinkedHashMap<>();
                dataset.put("label", item.name());
                dataset.put("data", points);
                dataset.put("backgroundColor", color(index, 0.75));
                dataset.put("pointRadius", 5);
                datasets.add(dataset);
            }
        } else {
            List<Object> labels = labelsFor(series.get(0));
            chartData.put("labels", labels);
            for (int index = 0; index < series.size(); index++) {
                Series item = series.get(index);
                if (item.y().size() != labels.size()) {
                    throw new IllegalArgumentException("各系列的数据点数量必须一致");
                }
                Map<String, Object> dataset = new LinkedHashMap<>();
                dataset.put("label", item.name());
                dataset.put("data", item.y());
                dataset.put("borderColor", color(index, 1));
                dataset.put("backgroundColor", color(index, "line".equals(type) ? 0.2 : 0.72));
                dataset.put("borderWidth", 3);
                if ("line".equals(type)) {
                    dataset.put("fill", false);
                    dataset.put("tension", 0.25);
                    dataset.put("pointRadius", 4);
                } else {
                    dataset.put("borderRadius", 6);
                    dataset.put("borderSkipped", false);
                }
                datasets.add(dataset);
            }
        }
        chartData.put("datasets", datasets);
        chart.put("data", chartData);
        chart.put("options", chartOptions(type, title));
        return chart;
    }

    private List<Series> parseSeries(JsonNode root) {
        if (root == null || root.isNull()) return List.of();
        List<Series> result = new ArrayList<>();
        if (root.isArray()) {
            result.add(new Series("数据", List.of(), numericValues(root)));
        } else if (root.isObject() && root.has("series") && root.get("series").isArray()) {
            int index = 1;
            for (JsonNode item : root.get("series")) result.add(parseSeriesItem(item, index++));
        } else if (root.isObject() && root.has("y")) {
            result.add(parseSeriesItem(root, 1));
        } else if (root.isObject()) {
            List<Object> labels = new ArrayList<>();
            List<Number> values = new ArrayList<>();
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber()) {
                    labels.add(entry.getKey());
                    values.add(entry.getValue().numberValue());
                }
            });
            result.add(new Series("数据", labels, values));
        }
        result.removeIf(item -> item.y().isEmpty());
        int totalPoints = result.stream().mapToInt(item -> item.y().size()).sum();
        if (totalPoints > MAX_POINTS) throw new IllegalArgumentException("数据点超过 1000 个，请先聚合或筛选");
        return result;
    }

    private Series parseSeriesItem(JsonNode item, int index) {
        if (item == null || !item.isObject()) throw new IllegalArgumentException("series 中的每一项必须是对象");
        String name = item.path("name").asText("系列 " + index);
        List<Object> x = item.has("x") ? values(item.get("x")) : List.of();
        List<Number> y = item.has("y") ? numericValues(item.get("y")) : List.of();
        return new Series(name, x, y);
    }

    private List<Object> values(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("x 必须是数组");
        List<Object> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isNumber()) result.add(value.numberValue());
            else if (value.isTextual()) result.add(value.asText());
            else result.add(value.toString());
        }
        return result;
    }

    private List<Number> numericValues(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("y 必须是数值数组");
        List<Number> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isNumber()) throw new IllegalArgumentException("图表数值中包含非数字内容");
            result.add(value.numberValue());
        }
        return result;
    }

    private List<Object> labelsFor(Series series) {
        if (!series.x().isEmpty()) {
            if (series.x().size() != series.y().size()) throw new IllegalArgumentException("x 与 y 数据数量必须一致");
            return series.x();
        }
        List<Object> labels = new ArrayList<>();
        for (int i = 0; i < series.y().size(); i++) labels.add("第 " + (i + 1) + " 项");
        return labels;
    }

    private Map<String, Object> chartOptions(String type, String title) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("responsive", false);
        options.put("maintainAspectRatio", false);
        options.put("animation", false);
        options.put("layout", Map.of("padding", 24));
        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("title", Map.of(
            "display", title != null && !title.isBlank(),
            "text", title == null ? "" : title,
            "color", "#0F172A",
            "font", Map.of("family", "Noto Sans SC", "size", 24, "weight", "bold"),
            "padding", Map.of("bottom", 22)));
        plugins.put("legend", Map.of(
            "position", "bottom",
            "labels", Map.of("color", "#334155", "padding", 18, "font", Map.of("family", "Noto Sans SC", "size", 13))));
        options.put("plugins", plugins);
        if (!"pie".equals(type)) {
            options.put("scales", Map.of(
                "x", Map.of("grid", Map.of("color", "#E2E8F0"), "ticks", Map.of("color", "#475569")),
                "y", Map.of("beginAtZero", true, "grid", Map.of("color", "#E2E8F0"), "ticks", Map.of("color", "#475569"))));
        }
        return options;
    }

    private Path createOutputPath(String title) throws Exception {
        String session = safeName(ToolUserContext.getSessionId(), "session");
        Path directory = Path.of(System.getProperty("user.home"), ".mindpet", "generated-files", session);
        Files.createDirectories(directory);
        String base = safeName(title, "chart");
        return Files.createTempFile(directory, base + "-" + FILE_TIME.format(LocalDateTime.now()) + "-", ".png");
    }

    private String safeName(String value, String fallback) {
        String safe = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        safe = safe.replaceAll("\\s+", "_").replaceAll("_+", "_");
        if (safe.isBlank()) safe = fallback;
        return safe.length() > 48 ? safe.substring(0, 48) : safe;
    }

    private String markdownLabel(String title) {
        String label = title == null || title.isBlank() ? "数据图表" : title;
        return label.replace("[", "（").replace("]", "）");
    }

    private String toLocalFileUrl(Path path) {
        return "local-file:///" + path.toAbsolutePath().toString().replace('\\', '/');
    }

    private boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
            && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
    }

    private List<String> colors(int count, int offset, double alpha) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(color(i + offset, alpha));
        return result;
    }

    private String color(int index, double alpha) {
        String hex = COLORS.get(Math.floorMod(index, COLORS.size()));
        if (alpha >= 1) return hex;
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String oneLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= maxLength ? oneLine : oneLine.substring(0, maxLength) + "...";
    }

    private record Series(String name, List<Object> x, List<Number> y) {}
}
