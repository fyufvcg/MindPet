package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.TaobaoMcpClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DeliveryTools {

    private final TaobaoMcpClient client;

    @Autowired
    public DeliveryTools(TaobaoMcpClient client) {
        this.client = client;
    }

    @Tool(description = "检查淘宝闪购/饿了么登录状态。")
    public String shangou_check_login() {
        return client.callTool("shangou_check_login", Map.of());
    }

    @Tool(description = "打开淘宝闪购/饿了么登录页，需用户手动登录。")
    public String shangou_open_login() {
        return client.callTool("shangou_open_login", Map.of());
    }

    @Tool(description = "列出账号内常用收货地址。")
    public String shangou_list_addresses() {
        return client.callTool("shangou_list_addresses", Map.of());
    }

    @Tool(description = "选择收货地址以确定配送范围。")
    public String shangou_set_address(
        @ToolParam(description = "地址关键字，可为空") String keyword,
        @ToolParam(description = "地址序号，从0开始，可为0") int index
    ) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword == null ? "" : keyword);
        args.put("index", index);
        return client.callTool("shangou_set_address", args);
    }

    @Tool(description = "搜索附近可配送的店铺或美食。")
    public String shangou_search(
        @ToolParam(description = "搜索关键字，如奶茶、汉堡、水果") String keyword,
        @ToolParam(description = "返回条数，默认10") int limit
    ) {
        return client.callTool("shangou_search", Map.of("keyword", keyword, "limit", limit <= 0 ? 10 : limit));
    }

    @Tool(description = "查看某家店铺的菜单。")
    public String shangou_shop_menu(
        @ToolParam(description = "店铺ID或店铺URL") String shop,
        @ToolParam(description = "返回条数，默认10") int limit
    ) {
        return client.callTool("shangou_shop_menu", Map.of("shop", shop, "limit", limit <= 0 ? 10 : limit));
    }

    @Tool(description = "把某个菜品加入购物车。")
    public String shangou_add_to_cart(
        @ToolParam(description = "店铺ID或店铺URL") String shop,
        @ToolParam(description = "菜品名称") String item,
        @ToolParam(description = "份数，默认1") int quantity
    ) {
        return client.callTool("shangou_add_to_cart", Map.of(
            "shop", shop,
            "item", item,
            "quantity", quantity <= 0 ? 1 : quantity
        ));
    }

    @Tool(description = "查看购物车内容。")
    public String shangou_view_cart(
        @ToolParam(description = "店铺ID或店铺URL") String shop
    ) {
        return client.callTool("shangou_view_cart", Map.of("shop", shop));
    }

    @Tool(description = "生成待支付订单，到支付页前停止。")
    public String shangou_create_order(
        @ToolParam(description = "店铺ID或店铺URL") String shop
    ) {
        return client.callTool("shangou_create_order", Map.of("shop", shop));
    }

    @Tool(description = "提交订单并获取支付链接，不会代付。")
    public String shangou_submit_order(
        @ToolParam(description = "店铺ID或店铺URL") String shop
    ) {
        return client.callTool("shangou_submit_order", Map.of("shop", shop));
    }

    @Tool(description = "查询外卖服务状态和环境配置。")
    public String shangou_get_server_status() {
        return client.callTool("shangou_get_server_status", Map.of());
    }
}
