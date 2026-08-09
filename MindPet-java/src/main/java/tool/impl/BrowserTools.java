package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.PlaywrightBrowserService;

/**
 * 浏览器操控工具 — 直接使用 Playwright Java SDK，不经过 MCP。
 * LLM 使用这些工具来操控浏览器，通过 CSS selector 定位元素。
 */
@Component
public class BrowserTools {

    private final PlaywrightBrowserService browser;

    @Autowired
    public BrowserTools(PlaywrightBrowserService browser) {
        this.browser = browser;
    }

    @Tool(description = """
        导航到指定 URL。返回页面快照（含可交互元素列表）。\n
        可用选择器格式：CSS selector（如 #id, .class, input[name="q"], button, a[href="/login"]）。
        """)
    public String browser_navigate(
            @ToolParam(description = "URL to navigate to") String url) {
        return browser.navigate(url);
    }

    @Tool(description = """
        点击页面元素。selector 从 browser_snapshot 输出的 [索引] 后面的 CSS 选择器获取。\n
        例如 browser_click("#login-btn")、browser_click("a[href='/home']")、browser_click("button.submit")。
        """)
    public String browser_click(
            @ToolParam(description = "CSS selector from snapshot (e.g. #id, .class, tag[name='value'])") String selector) {
        return browser.click(selector);
    }

    @Tool(description = "在输入框中输入文本（不清空已有内容）。selector 从 snapshot 获取。")
    public String browser_type(
            @ToolParam(description = "CSS selector from snapshot") String selector,
            @ToolParam(description = "Text to type") String text) {
        return browser.type(selector, text);
    }

    @Tool(description = "清空输入框并填入文本（覆盖已有内容）。")
    public String browser_fill(
            @ToolParam(description = "CSS selector from snapshot") String selector,
            @ToolParam(description = "Text to fill") String text) {
        return browser.fill(selector, text);
    }

    @Tool(description = "截取当前页面的截图。")
    public String browser_screenshot() {
        byte[] img = browser.screenshot();
        if (img == null) return "截图失败：浏览器未就绪";
        return "截图已生成 (" + (img.length / 1024) + " KB)";
    }

    @Tool(description = """
        获取当前页面的文本快照。\n
        返回页面标题、URL、所有可交互元素的 [索引] CSS选择器 "文本"。\n
        重要：之后的 browser_click、browser_type 等操作，selector 参数必须从这里输出的 CSS 选择器中选取！
        """)
    public String browser_snapshot() {
        return browser.snapshot();
    }

    @Tool(description = "悬停在元素上，触发 hover 效果。")
    public String browser_hover(
            @ToolParam(description = "CSS selector from snapshot") String selector) {
        return browser.hover(selector);
    }

    @Tool(description = "在下拉框中选择一个选项。")
    public String browser_select(
            @ToolParam(description = "CSS selector from snapshot") String selector,
            @ToolParam(description = "Value to select") String value) {
        return browser.select(selector, value);
    }

    @Tool(description = "按下键盘按键（Enter、Escape、Tab、ArrowDown 等）。")
    public String browser_press_key(
            @ToolParam(description = "Key name (Enter, Escape, Tab, ArrowDown, PageDown etc.)") String key) {
        return browser.pressKey(key);
    }

    @Tool(description = "关闭当前页面并打开新空白页。")
    public String browser_close() {
        return browser.closePage();
    }

    @Tool(description = "返回上一页。")
    public String browser_navigate_back() {
        return browser.goBack();
    }

    @Tool(description = "等待指定毫秒数。用于等待页面加载或动画完成。")
    public String browser_wait_for(
            @ToolParam(description = "Wait time in milliseconds (e.g. 1000 for 1 second)") String time) {
        try {
            return browser.waitFor(Integer.parseInt(time));
        } catch (NumberFormatException e) {
            return "时间参数无效: " + time;
        }
    }
}
