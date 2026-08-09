package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * AniList GraphQL API 服务 — 直接 HTTP POST 查动漫数据。
 * 免费公开 API，无需认证 (https://anilist.gitbook.io/anilist-apiv2-docs)。
 */
@Service
public class AniListService {

    private static final String API_URL = "https://graphql.anilist.co";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    public AniListService(Logger logger) {
        this.logger = logger;
    }

    // ==================== 公开方法 ====================

    /** 搜索动漫/漫画 */
    public JsonNode searchMedia(String title, String type, String genre, Integer year, String format,
                                 String status, int page, int perPage) {
        StringBuilder query = new StringBuilder();
        query.append("query($search:String,$type:MediaType,$genre:String,$year:Int,$format:MediaFormat,$status:MediaStatus,$page:Int,$perPage:Int){");
        query.append("Page(page:$page,perPage:$perPage){pageInfo{total currentPage lastPage hasNextPage}");
        query.append("media(search:$search,type:$type,genre:$genre,seasonYear:$year,format:$format,status:$status,sort:POPULARITY_DESC){");
        query.append("id title{romaji native} format episodes genres averageScore}}");
        query.append("}");

        ObjectNode vars = mapper.createObjectNode();
        // AniList 不接受 null 作为 search 参数，空字符串 OK
        vars.put("search", title != null && !title.isEmpty() ? title : "");
        if (type != null && !type.isEmpty()) vars.put("type", type.toUpperCase());
        if (genre != null && !genre.isEmpty()) vars.put("genre", genre);
        if (year != null) vars.put("year", year);
        if (format != null && !format.isEmpty()) vars.put("format", format.toUpperCase());
        if (status != null && !status.isEmpty()) vars.put("status", status.toUpperCase());
        vars.put("page", page);
        vars.put("perPage", perPage);

        return execute(query.toString(), vars);
    }

    /** 获取动漫/漫画详情 */
    public JsonNode getMedia(int id) {
        String query = "query($id:Int){Media(id:$id){id title{romaji english native}format status episodes duration season seasonYear genres averageScore popularity description(asHtml:false)studios{nodes{name}}characters(sort:ROLE,perPage:10){nodes{name{full}}}relations{edges{relationType node{id title{romaji}format seasonYear}}}recommendations(sort:RATING_DESC,perPage:5){nodes{mediaRecommendation{id title{romaji}averageScore}}}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("id", id);
        return execute(query, vars);
    }

    /** 新番放送表 */
    public JsonNode getSchedule(String season, Integer year, int page, int perPage) {
        String query = "query($s:MediaSeason,$y:Int,$p:Int,$pp:Int){Page(page:$p,perPage:$pp){pageInfo{total}media(season:$s,seasonYear:$y,type:ANIME,sort:POPULARITY_DESC){id title{romaji}format episodes genres averageScore}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("s", season != null ? season.toUpperCase() : null);
        vars.put("y", year);
        vars.put("p", page);
        vars.put("pp", perPage);
        return execute(query, vars);
    }

    /** 系列关系/观看顺序 */
    public JsonNode getRelations(int mediaId) {
        String query = "query($id:Int){Media(id:$id){id title{romaji} relations{edges{relationType node{id title{romaji native} format seasonYear}}}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("id", mediaId);
        return execute(query, vars);
    }

    /** 角色搜索 */
    public JsonNode searchCharacters(String name, int page, int perPage) {
        String query = "query($search:String,$page:Int,$perPage:Int){Page(page:$page,perPage:$perPage){pageInfo{total}";
        query += "characters(search:$search,sort:FAVOURITES_DESC){id name{full native} image{medium} media(sort:POPULARITY_DESC,perPage:5){nodes{id title{romaji}}}}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("search", name);
        vars.put("page", page);
        vars.put("perPage", perPage);
        return execute(query, vars);
    }

    /** 推荐 */
    public JsonNode getRecommendations(int mediaId) {
        String query = "query($id:Int){Media(id:$id){id title{romaji} recommendations(sort:RATING_DESC,perPage:10){nodes{" +
            "mediaRecommendation{id title{romaji native} format averageScore genres coverImage{large}}}}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("id", mediaId);
        return execute(query, vars);
    }

    /** 排行榜 */
    public JsonNode getRankings(String type, String sort, int page, int perPage) {
        String sortField = switch (sort != null ? sort.toLowerCase() : "trending") {
            case "top" -> "SCORE_DESC";
            case "popular" -> "POPULARITY_DESC";
            case "favourite" -> "FAVOURITES_DESC";
            default -> "TRENDING_DESC";
        };
        String query = "query($type:MediaType,$sort:[MediaSort],$page:Int,$perPage:Int){Page(page:$page,perPage:$perPage){pageInfo{total}";
        query += "media(type:$type,sort:$sort){id title{romaji} format averageScore popularity}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("type", type != null ? type.toUpperCase() : "ANIME");
        vars.put("sort", mapper.createArrayNode().add(sortField));
        vars.put("page", page);
        vars.put("perPage", perPage);
        return execute(query, vars);
    }

    /** 制作公司信息 */
    public JsonNode searchStudio(String name, int page, int perPage) {
        String query = "query($search:String,$page:Int,$perPage:Int){Page(page:$page,perPage:$perPage){pageInfo{total}";
        query += "studios(search:$search,sort:FAVOURITES_DESC){id name favourites isAnimationStudio media(sort:POPULARITY_DESC,perPage:10){nodes{id title{romaji} format seasonYear averageScore}}}}}";
        ObjectNode vars = mapper.createObjectNode();
        vars.put("search", name);
        vars.put("page", page);
        vars.put("perPage", perPage);
        return execute(query, vars);
    }

    // ==================== 内部方法 ====================

    private JsonNode execute(String query, ObjectNode variables) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.set("variables", variables);

            String jsonBody = mapper.writeValueAsString(body);
            URL url = new URI(API_URL).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                // 读取错误响应体
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder eb = new StringBuilder();
                    String el;
                    while ((el = er.readLine()) != null) eb.append(el);
                    logger.log("WARN", "AniList API 返回 " + status + " body: " + eb);
                } catch (Exception ignored) {}
                logger.log("WARN", "请求体: " + jsonBody.substring(0, Math.min(500, jsonBody.length())));
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return mapper.readTree(sb.toString());
            }
        } catch (Exception e) {
            logger.log("ERROR", "AniList API 调用失败: " + e.getMessage());
            return null;
        }
    }
}
