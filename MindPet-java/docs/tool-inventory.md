# MindPet 工具清单

本次调整前，通过正在运行的后端 `GET /api/desktop/tools/catalog` 读取到 103 个工具：Java 47 个、桌面 MCP 56 个。当前没有连接米家服务，但后端仍保留了米家路由。

本次调整后的内置工具范围为 64 个：Java 9 个、桌面 MCP 55 个。另有 7 个任务与能力管理工具在执行任务时提供。运行中的进程需要重新构建、启动后才会加载修改；以下数量是内置范围，用户接入额外通用 MCP 后会变化。工具存在也不代表当前环境配置齐全或一次调用就能完成任务。

## 保留的完整工具清单

桌面工具在后端的完整名称格式为 `desktop_tools__desktop__分类__工具名`。以下桌面行列出分类与末尾工具名，Java 工具名称没有前缀。

| 能力 | 来源 / 分类 | 数量 | 工具名 |
| --- | --- | ---: | --- |
| 文件操作 | 桌面 / `file` | 8 | `read_file`、`list_directory`、`get_file_metadata`、`find_files`、`write_file`、`edit_file`、`move_file`、`delete_file` |
| 文件内容搜索 | 桌面 / `search` | 1 | `grep_content` |
| 终端与进程 | 桌面 / `terminal` | 4 | `run_command`、`get_command_output`、`kill_command`、`run_terminal_command` |
| 联网搜索与正文抓取 | 桌面 / `browser` | 2 | `web_search`、`web_fetch` |
| 浏览器操作 | 桌面 / `browser` | 8 | `browser_connect`、`browser_tabs`、`browser_select_tab`、`browser_navigate`、`browser_search`、`browser_snapshot`、`browser_click`、`browser_click_ref` |
| 办公文档 | 桌面 / `office` | 2 | `load_office_skill`、`run_office_skill` |
| 电脑操作 | 桌面 / `computer` | 8 | `screenshot`、`mouse_move`、`mouse_click`、`mouse_scroll`、`type_text`、`key_press`、`get_windows`、`focus_window` |
| 系统与任务辅助 | 桌面 / `system` | 5 | `get_system_status`、`request_user_clarification`、`manage_cron_task`、`trigger_memory_purify`、`append_memory_summary` |
| 本地自动化 | 桌面 / `rpa` | 12 | `rpa_search_workflows`、`rpa_describe_workflow`、`rpa_run_workflow`、`rpa_get_run_status`、`rpa_cancel_run`、`rpa_start_recording`、`rpa_pause_recording`、`rpa_resume_recording`、`rpa_get_recording_status`、`rpa_finish_recording`、`rpa_bind_recording_secret`、`rpa_cancel_recording` |
| 基础计算 | Java | 3 | `numericCalculate`、`dateCalculate`、`convertTimezone` |
| 邮件办公 | Java | 6 | `listRecentMails`、`searchMails`、`getMailDetail`、`sendMail`、`replyMail`、`deleteMail` |

办公工具按需加载具体操作的参数与能力，支持 Word、Excel、PDF、PPTX 等文档；无需给每个文件格式的每个动作都注册一个模型工具。历史 `generate_file`、`modify_docx_file`、`modify_xlsx_file` 在本次调整前就已隐藏，不属于这次统计的 103 个工具。

任务管理工具：`agent_discover_tools`（发现并启用能力）、`agent_update_plan`（记录计划和进度）、`agent_read_output`（分页读取长输出）、`agent_report_completion`（记录交付及验证证据）。另有 `agent_discover_skills`、`agent_load_skill`、`agent_read_skill_resource` 提供 Skill 渐进式披露。这 7 个内部工具不属于静态工具目录。

后台记忆整理另有 3 个内部工具：`saveUserProfile`、`saveUserInsight`、`saveLlmGrowth`。它们只提供给独立的记忆整理任务，不向主对话 Agent 开放，也不计入静态目录的 64 个工具。

## 从模型入口移除的完整清单

本次从原 103 个工具中移除 39 个：38 个 Java 专用工具和 1 个桌面物理定位工具。历史实现源码保留，但不再通过 Java 工具注册、路由、目录或动态发现提供给模型。

| 原能力 | 数量 | 移除的工具名 |
| --- | ---: | --- |
| 天气及订阅 | 5 | `getWeather`、`subscribeWeather`、`unsubscribeWeather`、`showSubscription`、`sunSkySpots` |
| 动漫查询 | 8 | `anime_search_media`、`anime_get_media`、`anime_get_schedule`、`anime_get_relations`、`anime_find_characters`、`anime_get_recommendations`、`anime_get_rankings`、`anime_get_studio` |
| 滴滴打车 | 5 | `didi_searchPlace`、`didi_estimateRide`、`didi_createOrder`、`didi_queryOrder`、`didi_cancelOrder` |
| IP 定位及路线 | 2 | `ipGeolocation`、`routePlanning` |
| 麦当劳 | 4 | `mcdonaldOrder`、`mcdonaldCampaigns`、`mcdonaldCoupons`、`mcdonaldOrderStatus` |
| 固定发票报销流程 | 3 | `reimburseInvoice`、`reverifyInvoice`、`queryReimbursements` |
| 固定类型远程图表 | 4 | `chart_bar`、`chart_line`、`chart_pie`、`chart_scatter` |
| 语音包装 | 2 | `changeVoice`、`speakText` |
| 外部翻译接口 | 1 | `translateText` |
| 专用汇率接口 | 1 | `convertCurrency` |
| 桌面物理定位 | 1 | `desktop_tools__desktop__system__get_location` |

另外移除米家路由的 13 个工具映射：`list_homes`、`list_devices`、`list_scenes`、`list_consumables`、`get_device_spec`、`get_device_properties`、`set_device_property`、`run_device_action`、`run_scene`、`get_statistics`、`run_speaker_command`、`login`、`login_status`。它们没有出现在本次运行时目录中，不计入上述 39 个。已知垂直业务 MCP 服务会被跳过；米家服务也会按设备工具组合识别，避免其登录、统计等通用名称通过动态发现重新进入模型。

## 能力选择原则

- 知识、概念解释、推理、普通翻译和一般建议优先由模型直接回答。
- 明天的天气等时效问题用通用 `web_search`，必要时 `web_fetch` 读取来源。确认地点、目标日期和发布时间，不能将当前观测结果当作未来预报。
- 图表、表格和文档使用通用办公工具或本地脚本；取消固定图表接口不取消图表生成能力。
- 发票文件可以通过文件、办公和本地操作处理；官方真伪核验仍需要真实可用的数据来源或服务，不能因存在通用工具就宣称已核验。
- 工具不支持目标时，发现其他通用能力并换方法；只有实际失败后才报告具体限制。搜索空结果不能当作工具不可用。
- Desktop 工具定义常驻，具体动作遵守用户任务和权限约束；外部 MCP 通过 `/` 选择进入当前任务。

## 注册与扩展

Java 仍使用 Spring AI `MethodToolCallbackProvider` 注册工具、`ToolCallAdvisor` 执行工具循环。`AiConfig` 明确注册通用计算和邮件 Bean，避免所有 `@Tool` 自动扫描导致旧工具复活。

`ToolExposurePolicy` 供路由、工具目录、动态发现和 MCP 注册共同使用。已知天气、米家、动漫、滴滴、麦当劳和远程固定图表服务受过滤。额外通用 MCP 服务仍可接入，并通过 `/` 为当前任务选择；系统不会仅凭陌生工具名自动判断所有第三方服务的业务范围，新增服务应按通用办公和本地操作原则选择。

这些调整改变模型可调用入口，不会清除已存储的天气订阅、历史业务记录或第三方配置。

能力加载结构详见 [Skill 与工具框架](capability-framework.md)。
