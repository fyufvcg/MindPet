package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.AniListService;

/**
 * 动漫查询工具 — 搜索、详情、新番、角色、排名、推荐、制作公司。
 * 底层调用 AniList GraphQL API（免费公开，无需认证）。
 */
@Component
public class AnimeTools {

    private final AniListService anilist;

    @Autowired
    public AnimeTools(AniListService anilist) {
        this.anilist = anilist;
    }

    @Tool(description = "搜索动漫或漫画。用户说找某部番时调用。注意：所有参数必须用英文，AniList 不接受中文。")
    public String anime_search_media(
            @ToolParam(description = "搜索关键词（标题），用英文或罗马音，如 Yuri、Girls Love、Lycoris Recoil") String title,
            @ToolParam(description = "ANIME 或 MANGA") String type,
            @ToolParam(description = "英文风格标签：Action Comedy Romance Horror Girls Love Shoujo Ai 等") String genre,
            @ToolParam(description = "播出年份如 2026") String year,
            @ToolParam(description = "格式：TV MOVIE OVA ONA SPECIAL") String format) {
        Integer y = parseInt(year, (Integer) null);
        JsonNode result = anilist.searchMedia(
            title != null && !title.isBlank() ? title : null,
            type, genre, y, format, null, 1, 10);
        if (result == null) return "查询失败，请稍后再试~";
        JsonNode page = result.path("data").path("Page");
        JsonNode media = page.path("media");
        if (!media.isArray() || media.isEmpty()) return "没有找到相关作品~";
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(page.path("pageInfo").path("total").asInt()).append(" 部作品，前 ").append(media.size()).append(" 部：\n");
        for (JsonNode m : media) {
            sb.append("【").append(m.path("id").asInt()).append("】");
            JsonNode titleNode = m.path("title");
            String romaji = titleNode.path("romaji").asText("");
            String nativeTitle = titleNode.path("native").asText("");
            sb.append(romaji);
            if (!nativeTitle.isEmpty() && !nativeTitle.equals(romaji)) sb.append(" / ").append(nativeTitle);
            sb.append("\n  类型: ").append(m.path("format").asText("?"));
            if (m.has("episodes") && !m.path("episodes").isNull()) sb.append(" | ").append(m.path("episodes").asInt()).append("集");
            sb.append(" | 评分: ").append(m.path("averageScore").asInt(0)).append("/100");
            String genres = collectGenres(m);
            if (!genres.isEmpty()) sb.append(" | ").append(genres);
            sb.append("\n\n");
        }
        sb.append("查看详情请用 anime_get_media + 编号（如 anime_get_media(1)）。");
        return sb.toString().trim();
    }

    @Tool(description = "获取动漫/漫画完整详情：剧情简介、角色、评分、制作公司、续作关系。需要 AniList ID。")
    public String anime_get_media(
            @ToolParam(description = "AniList 作品 ID（从搜索结果获取）") String mediaId) {
        int id = parseInt(mediaId, -1);
        if (id <= 0) return "请提供有效的作品 ID~";
        JsonNode result = anilist.getMedia(id);
        if (result == null) return "查询失败~";
        JsonNode m = result.path("data").path("Media");
        if (m.isMissingNode()) return "未找到该作品~";
        StringBuilder sb = new StringBuilder();
        JsonNode t = m.path("title");
        sb.append(t.path("romaji").asText("?"));
        String nat = t.path("native").asText("");
        if (!nat.isEmpty()) sb.append(" / ").append(nat);
        sb.append("\n").append("=".repeat(40)).append("\n");
        sb.append("格式: ").append(m.path("format").asText("?"));
        if (m.has("episodes") && !m.path("episodes").isNull()) sb.append(" | ").append(m.path("episodes").asInt()).append("集");
        sb.append(" | 评分: ").append(m.path("averageScore").asInt(0)).append("/100");
        sb.append(" | 人气: ").append(m.path("popularity").asInt(0)).append("\n");
        sb.append("类型: ").append(collectGenres(m)).append("\n");
        JsonNode studios = m.path("studios").path("nodes");
        if (studios.isArray() && !studios.isEmpty()) {
            sb.append("制作: ");
            for (JsonNode s : studios) sb.append(s.path("name").asText("?")).append(" ");
            sb.append("\n");
        }
        String desc = m.path("description").asText("");
        if (!desc.isBlank()) {
            if (desc.length() > 400) desc = desc.substring(0, 400) + "...";
            sb.append("简介: ").append(desc).append("\n");
        }
        JsonNode chars = m.path("characters").path("nodes");
        if (chars.isArray() && !chars.isEmpty()) {
            sb.append("\n主要角色:\n");
            for (JsonNode c : chars) sb.append("  - ").append(c.path("name").path("full").asText("?")).append("\n");
        }
        JsonNode rels = m.path("relations").path("edges");
        if (rels.isArray() && !rels.isEmpty()) {
            sb.append("\n相关作品:\n");
            for (JsonNode r : rels) {
                sb.append("  ").append(r.path("relationType").asText("?")).append(" → ");
                sb.append(r.path("node").path("title").path("romaji").asText("?")).append("\n");
            }
        }
        return sb.toString().trim();
    }

    @Tool(description = "查询新番放送表。按季节和年份浏览，如 2026年夏季番。用户说这季有什么新番时调用。")
    public String anime_get_schedule(
            @ToolParam(description = "季节：WINTER/SPRING/SUMMER/FALL，默认当前季") String season,
            @ToolParam(description = "年份，默认今年") String year) {
        int y = parseInt(year, java.time.Year.now().getValue());
        if (season == null || season.isBlank()) {
            int month = java.time.LocalDate.now().getMonthValue();
            season = switch (month) { case 1,2,3 -> "WINTER"; case 4,5,6 -> "SPRING"; case 7,8,9 -> "SUMMER"; default -> "FALL"; };
        }
        JsonNode result = anilist.getSchedule(season, y, 1, 12);
        if (result == null) return "查询失败~";
        JsonNode media = result.path("data").path("Page").path("media");
        if (!media.isArray() || media.isEmpty()) return y + "年" + season + "季暂无数据~";
        StringBuilder sb = new StringBuilder(y + "年" + season + "季新番 (" + media.size() + "部):\n\n");
        for (JsonNode m : media) {
            sb.append("【").append(m.path("id").asInt()).append("】");
            sb.append(m.path("title").path("romaji").asText("?")).append("\n");
            sb.append("  格式: ").append(m.path("format").asText("?"));
            sb.append(" | 评分: ").append(m.path("averageScore").asInt(0));
            String genres = collectGenres(m);
            if (!genres.isEmpty()) sb.append(" | ").append(genres);
            JsonNode next = m.path("nextAiringEpisode");
            if (!next.isMissingNode() && !next.isNull()) {
                sb.append("\n  下集: 第").append(next.path("episode").asInt()).append("集");
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "查询某部作品的系列关系：续作、前传、外传、OVA等观看顺序。")
    public String anime_get_relations(
            @ToolParam(description = "AniList 作品 ID") String mediaId) {
        int id = parseInt(mediaId, -1);
        if (id <= 0) return "请提供有效的作品 ID~";
        JsonNode result = anilist.getRelations(id);
        if (result == null) return "查询失败~";
        JsonNode m = result.path("data").path("Media");
        JsonNode edges = m.path("relations").path("edges");
        if (!edges.isArray() || edges.isEmpty()) return "该作品没有相关续作/前传记录~";
        StringBuilder sb = new StringBuilder(m.path("title").path("romaji").asText("?")).append(" 的系列关系:\n\n");
        for (JsonNode e : edges) {
            String rel = e.path("relationType").asText("?");
            JsonNode n = e.path("node");
            sb.append(rel).append(" → 【").append(n.path("id").asInt()).append("】");
            sb.append(n.path("title").path("romaji").asText("?")).append(" (");
            sb.append(n.path("format").asText("?")).append(" ").append(n.path("seasonYear").asInt(0)).append(")\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "搜索动漫角色或声优。可按角色名或声优名搜索。")
    public String anime_find_characters(
            @ToolParam(description = "角色名称或声优名称") String name) {
        if (name == null || name.isBlank()) return "请输入角色或声优名称~";
        JsonNode result = anilist.searchCharacters(name, 1, 12);
        if (result == null) return "查询失败~";
        JsonNode chars = result.path("data").path("Page").path("characters");
        if (!chars.isArray() || chars.isEmpty()) return "未找到「" + name + "」的相关角色~";
        StringBuilder sb = new StringBuilder("搜索「" + name + "」找到 " + chars.size() + " 个角色:\n\n");
        for (JsonNode c : chars) {
            sb.append(c.path("name").path("full").asText("?")).append(" / ");
            sb.append(c.path("name").path("native").asText(""));
            sb.append("\n  出演作品: ");
            JsonNode media = c.path("media").path("nodes");
            if (media.isArray()) {
                for (int i = 0; i < Math.min(media.size(), 3); i++)
                    sb.append(media.get(i).path("title").path("romaji").asText("?")).append(" / ");
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "获取某部作品的推荐：喜欢这部的人还喜欢哪些。需要 AniList ID。")
    public String anime_get_recommendations(
            @ToolParam(description = "AniList 作品 ID") String mediaId) {
        int id = parseInt(mediaId, -1);
        if (id <= 0) return "请提供有效的作品 ID~";
        JsonNode result = anilist.getRecommendations(id);
        if (result == null) return "查询失败~";
        JsonNode recs = result.path("data").path("Media").path("recommendations").path("nodes");
        if (!recs.isArray() || recs.isEmpty()) return "暂无推荐数据~";
        StringBuilder sb = new StringBuilder("推荐作品:\n\n");
        for (JsonNode r : recs) {
            JsonNode mr = r.path("mediaRecommendation");
            sb.append("【").append(mr.path("id").asInt()).append("】");
            sb.append(mr.path("title").path("romaji").asText("?")).append(" (");
            sb.append(mr.path("format").asText("?")).append(")");
            sb.append(" 评分: ").append(mr.path("averageScore").asInt(0)).append("/100\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "查询动漫排行榜：热门、高分、趋势等。")
    public String anime_get_rankings(
            @ToolParam(description = "排行方式：trending/hot、top/score、popular。默认 trending") String sort,
            @ToolParam(description = "类型：ANIME 或 MANGA，默认 ANIME") String type) {
        JsonNode result = anilist.getRankings(type, sort, 1, 12);
        if (result == null) return "查询失败~";
        JsonNode media = result.path("data").path("Page").path("media");
        if (!media.isArray() || media.isEmpty()) return "暂无排行数据~";
        String label = switch (sort != null ? sort.toLowerCase() : "") {
            case "top", "score" -> "评分";
            case "popular" -> "人气";
            default -> "热门趋势";
        };
        StringBuilder sb = new StringBuilder("AniList " + label + "排行:\n\n");
        int rank = 1;
        for (JsonNode m : media) {
            sb.append(rank++).append(". 【").append(m.path("id").asInt()).append("】");
            sb.append(m.path("title").path("romaji").asText("?")).append(" (");
            sb.append(m.path("format").asText("?")).append(")");
            sb.append(" 评分:").append(m.path("averageScore").asInt(0));
            sb.append(" 人气:").append(m.path("popularity").asInt(0)).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "搜索动画制作公司，查看其代表作品。")
    public String anime_get_studio(
            @ToolParam(description = "制作公司名称，如 Kyoto Animation, ufotable, MAPPA") String name) {
        if (name == null || name.isBlank()) return "请输入制作公司名称~";
        JsonNode result = anilist.searchStudio(name, 1, 3);
        if (result == null) return "查询失败~";
        JsonNode studios = result.path("data").path("Page").path("studios");
        if (!studios.isArray() || studios.isEmpty()) return "未找到「" + name + "」的制作公司~";
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : studios) {
            sb.append(s.path("name").asText("?")).append(" | 收藏:").append(s.path("favourites").asInt(0));
            sb.append(" | 动画公司:").append(s.path("isAnimationStudio").asBoolean() ? "是" : "否").append("\n");
            JsonNode works = s.path("media").path("nodes");
            if (works.isArray()) {
                sb.append("  代表作品: ");
                for (int i = 0; i < Math.min(works.size(), 8); i++) {
                    JsonNode w = works.get(i);
                    sb.append(w.path("title").path("romaji").asText("?")).append("(").append(w.path("seasonYear").asInt(0)).append(")");
                    if (i < Math.min(works.size(), 8) - 1) sb.append(", ");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 工具方法 ====================

    private String collectGenres(JsonNode m) {
        JsonNode genres = m.path("genres");
        if (!genres.isArray() || genres.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode g : genres) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(g.asText());
        }
        return sb.toString();
    }

    private int parseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s != null ? s.trim() : ""); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private Integer parseInt(String s, Integer defaultVal) {
        try { return Integer.parseInt(s != null ? s.trim() : ""); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
