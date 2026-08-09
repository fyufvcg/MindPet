package service;

import com.microsoft.playwright.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import util.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Playwright 浏览器服务 — 直接使用 Playwright Java SDK，不经过 MCP 中间层。
 * 应用启动时打开浏览器，运行期间保活，应用关闭时清理。
 */
@Service
public class PlaywrightBrowserService {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private final Logger logger;

    public PlaywrightBrowserService(Logger logger) {
        this.logger = logger;
    }

    private static final String CDP_ENDPOINT = "http://127.0.0.1:9222";

    @PostConstruct
    public void init() {
        try {
            playwright = Playwright.create();

            // 优先连接用户已有的浏览器（CDP），失败则自己启动独立浏览器
            if (tryConnectCDP()) {
                logger.log("INFO", "浏览器: 已连接用户浏览器 (CDP)");
                setupFromCDP();
            } else {
                logger.log("INFO", "浏览器: CDP 不可用，启动独立浏览器");
                launchOwnBrowser();
            }
            logger.log("INFO", "浏览器就绪: " + (page != null ? page.url() : "无"));
        } catch (Exception e) {
            logger.log("ERROR", "浏览器初始化失败: " + e.getMessage());
        }
    }

    private boolean tryConnectCDP() {
        try {
            browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void setupFromCDP() {
        var contexts = browser.contexts();
        if (!contexts.isEmpty()) {
            context = contexts.get(0);
            var pages = context.pages();
            page = pages.isEmpty() ? context.newPage() : pages.get(0);
        } else {
            context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1920, 1080).setLocale("zh-CN"));
            page = context.newPage();
        }
    }

    /** 回退方案：自己启动独立 MS Edge 浏览器（不需要用户浏览器开 CDP） */
    private void launchOwnBrowser() {
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setChannel("msedge")
            .setHeadless(false)
            .setArgs(java.util.List.of("--disable-blink-features=AutomationControlled")));
        context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(1920, 1080).setLocale("zh-CN"));
        page = context.newPage();
        page.navigate("about:blank");
    }

    @PreDestroy
    public void destroy() {
        try {
            if (page != null) page.close();
            if (context != null) context.close();
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            logger.log("INFO", "Playwright 浏览器已关闭");
        } catch (Exception e) {
            // ignore
        }
    }

    public boolean isReady() {
        return page != null && !page.isClosed();
    }

    public Page getPage() {
        return page;
    }

    // ==================== 高级操作 ====================

    public String navigate(String url) {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.navigate(url);
            page.waitForLoadState();
            Thread.sleep(2000); // 等 SPA 动态渲染完成
            return snapshot();
        } catch (Exception e) {
            return "导航失败: " + e.getMessage();
        }
    }

    public String snapshot() {
        if (!isReady()) return "浏览器未就绪";
        try {
            String js = """
                () => {
                    const title = document.title;
                    const url = document.location.href;

                    // 第一部分：页面结构文本（main 区域 > body 兜底）
                    const main = document.querySelector('main, [role="main"]') || document.body;
                    let pageText = main ? main.innerText.trim() : '';
                    if (pageText.length > 3000) pageText = pageText.substring(0, 3000) + '\\n...(文本截断)';

                    // 第二部分：所有可交互元素
                    const selectors = [
                        'a[href]', 'button', 'input', 'select', 'textarea',
                        '[role="button"]', '[role="link"]', '[role="textbox"]',
                        '[role="combobox"]', '[role="menuitem"]', '[role="tab"]',
                        '[role="switch"]', '[role="checkbox"]', '[role="radio"]',
                        '[role="option"]', '[contenteditable="true"]', '[onclick]'
                    ].join(',');
                    const elements = document.querySelectorAll(selectors);
                    const items = [];
                    const seen = new Set();
                    elements.forEach(el => {
                        const rect = el.getBoundingClientRect();
                        const aria = el.getAttribute('aria-label');
                        const titleAttr = el.title;
                        const isHidden = rect.width === 0 && rect.height === 0 && !aria && !titleAttr;
                        if (isHidden) return;
                        const tag = el.tagName.toLowerCase();
                        const id = el.id;
                        const hrefVal = el.href && tag === 'a' ? el.getAttribute('href') : null;
                        const inputName = (el.name && (tag === 'input' || tag === 'select' || tag === 'textarea')) ? el.name : null;

                        // 选择器：优先 id > href > name > 稳定 class
                        let selector;
                        if (id) {
                            selector = '#' + id;
                        } else if (hrefVal && !hrefVal.startsWith('#') && !hrefVal.startsWith('javascript:')) {
                            selector = tag + '[href="' + hrefVal + '"]';
                        } else if (inputName) {
                            selector = tag + '[name="' + inputName + '"]';
                        } else {
                            // 兜底：tag + 前2个稳定class（过滤明显动态的）
                            const classes = el.className && typeof el.className === 'string'
                                ? el.className.trim().split(/\\s+/)
                                    .filter(c => c.length < 25 && !c.includes('_') && !c.match(/^[a-z]+[A-Z]/))
                                    .slice(0, 2).join('.') : '';
                            selector = tag + (classes ? '.' + classes : '');
                        }
                        if (selector.length > 80) selector = selector.substring(0, 80);

                        // 文本标注：aria-label > title > textContent > value
                        let text = (aria || titleAttr || '').trim().substring(0, 60);
                        if (!text) {
                            text = (el.textContent || el.innerText || el.value || '').trim().substring(0, 60);
                        }
                        const placeholder = el.placeholder ? ' ☐' + el.placeholder : '';
                        const hrefShort = (hrefVal && tag === 'a') ? ' ↗' + hrefVal : '';

                        const key = selector + '|' + text;
                        if (!seen.has(key) && (text || placeholder || hrefShort)) {
                            seen.add(key);
                            items.push('[' + items.length + '] ' + selector + ' "' + text + '"' + placeholder + hrefShort);
                        }
                    });

                    let elSection = '';
                    if (items.length > 0) {
                        elSection = '\\n--- 交互元素 (' + items.length + '个) ---\\n' + items.join('\\n');
                    }

                    return 'Title: ' + title + '\\nURL: ' + url + '\\n\\n' + pageText + elSection;
                }
                """;
            Object raw = page.evaluate(js);
            if (raw != null) {
                String result = raw.toString();
                if (result.length() > 10000) result = result.substring(0, 10000) + "\n...(截断)";
                return result;
            }
            return "快照失败: 无响应";
        } catch (Exception e) {
            return "快照失败: " + e.getMessage();
        }
    }

    public String click(String selector) {
        if (!isReady()) return "浏览器未就绪";
        try {
            // 先尝试 CSS selector（短超时，避免卡 30s）
            Locator loc = page.locator(selector).first();
            try {
                loc.click(new Locator.ClickOptions().setTimeout(3000));
            } catch (Exception cssEx) {
                // CSS 选择器失效（动态类名），尝试用 aria-label 或文本匹配
                String[] parts = selector.split(" \"");
                String label = parts.length > 1 ? parts[1].replace("\"", "").trim() : null;
                if (label != null && !label.isEmpty()) {
                    logger.log("INFO", "CSS 选择器失效，尝试文本匹配: \"" + label + "\"");
                    page.getByText(label, new Page.GetByTextOptions().setExact(true)).first()
                        .click(new Locator.ClickOptions().setTimeout(5000));
                } else {
                    throw cssEx;
                }
            }
            page.waitForLoadState();
            // 等 JS 渲染完成
            Thread.sleep(1500);
            return snapshot();
        } catch (Exception e) {
            return "点击失败 (" + selector + "): " + e.getMessage();
        }
    }

    public String type(String selector, String text) {
        if (!isReady()) return "浏览器未就绪";
        try {
            Locator loc = page.locator(selector).first();
            try {
                loc.fill(text, new Locator.FillOptions().setTimeout(3000));
            } catch (Exception cssEx) {
                // CSS 失效，用 aria-label 或 placeholder 匹配
                String[] parts = selector.split(" \"");
                String label = parts.length > 1 ? parts[1].replace("\"", "").trim() : null;
                if (label != null && !label.isEmpty()) {
                    page.getByLabel(label).first().fill(text, new Locator.FillOptions().setTimeout(5000));
                } else {
                    throw cssEx;
                }
            }
            return "已输入: " + text;
        } catch (Exception e) {
            return "输入失败 (" + selector + "): " + e.getMessage();
        }
    }

    public String fill(String selector, String text) {
        return type(selector, text); // fill 和 type 在 Playwright 中行为一致
    }

    public String hover(String selector) {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.locator(selector).first().hover();
            return "已悬停: " + selector;
        } catch (Exception e) {
            return "悬停失败: " + e.getMessage();
        }
    }

    public String select(String selector, String value) {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.locator(selector).first().selectOption(value);
            return "已选择: " + value;
        } catch (Exception e) {
            return "选择失败: " + e.getMessage();
        }
    }

    public String pressKey(String key) {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.keyboard().press(key);
            return "已按键: " + key;
        } catch (Exception e) {
            return "按键失败: " + e.getMessage();
        }
    }

    public byte[] screenshot() {
        if (!isReady()) return null;
        try {
            return page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        } catch (Exception e) {
            return null;
        }
    }

    public String goBack() {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.goBack();
            page.waitForLoadState();
            return snapshot();
        } catch (Exception e) {
            return "后退失败: " + e.getMessage();
        }
    }

    public String closePage() {
        if (!isReady()) return "浏览器未就绪";
        try {
            page.close();
            page = context.newPage();
            page.navigate("about:blank");
            return "页面已关闭并重置";
        } catch (Exception e) {
            return "关闭失败: " + e.getMessage();
        }
    }

    public String waitFor(int ms) {
        if (!isReady()) return "浏览器未就绪";
        try {
            Thread.sleep(ms);
            return snapshot();
        } catch (Exception e) {
            return "等待失败: " + e.getMessage();
        }
    }
}
