"""Generate the deterministic MindPet retrieval benchmark (120 memories/40 queries).

This script is deliberately offline: it never connects to PostgreSQL, Redis, Ollama,
the Java API, or any other service. Existing non-empty output files are protected
unless ``--force`` is supplied explicitly.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


USER_ID = "eval_test_user"

# Each category contains exactly ten memories. Metadata is explicit so benchmark
# changes remain reviewable rather than being hidden behind random generation.
MEMORY_GROUPS = {
    "sport": [
        ("我最喜欢的运动是羽毛球。", .90, 1.00, 3, "positive", 12, ["羽毛球", "最喜欢", "运动"]),
        ("我偶尔会和同事打篮球，但频率不高。", .52, .95, 2, "positive", 72, ["篮球", "同事", "偶尔"]),
        ("我通常周三晚上去游泳。", .70, .98, 2, "positive", 36, ["游泳", "周三", "晚上"]),
        ("我最近在尝试早晨慢跑。", .66, .92, 2, "positive", 24, ["慢跑", "早晨", "最近"]),
        ("下雨天我更愿意在室内做拉伸。", .48, .93, 1, "neutral", 96, ["下雨", "室内", "拉伸"]),
        ("我的膝盖不适合长距离跑步。", .86, .99, 3, "negative", 18, ["膝盖", "跑步", "限制"]),
        ("打羽毛球时，我更喜欢双打而不是单打。", .68, .97, 2, "positive", 48, ["羽毛球", "双打", "偏好"]),
        ("我常去城北体育馆运动。", .62, .95, 2, "positive", 72, ["城北体育馆", "运动", "地点"]),
        ("星期日通常是我的休息日，不安排训练。", .58, .96, 2, "neutral", 120, ["星期日", "休息", "训练"]),
        ("我的日常步数目标是一万步。", .60, .94, 1, "positive", 168, ["步数", "一万步", "目标"]),
    ],
    "food": [
        ("我最喜欢日料，尤其是三文鱼寿司。", .84, .99, 3, "positive", 24, ["日料", "三文鱼寿司", "最喜欢"]),
        ("我不吃香菜，点餐时需要特别去掉。", .91, 1.00, 3, "negative", 8, ["香菜", "忌口", "点餐"]),
        ("我的口味不太能接受很辣的食物。", .76, .98, 2, "negative", 36, ["辣", "口味", "忌口"]),
        ("工作日早上我喝不加糖的美式咖啡。", .65, .97, 2, "positive", 48, ["美式咖啡", "无糖", "工作日"]),
        ("周末下午我偶尔会点一杯拿铁。", .45, .92, 1, "positive", 96, ["拿铁", "周末", "偶尔"]),
        ("我对贝类海鲜过敏。", .95, 1.00, 3, "negative", 6, ["贝类", "海鲜", "过敏"]),
        ("我在家最常做的菜是番茄炒蛋。", .58, .96, 2, "positive", 72, ["番茄炒蛋", "做饭", "家常菜"]),
        ("主食方面，我通常选米饭而不是面条。", .55, .94, 1, "neutral", 120, ["米饭", "面条", "主食"]),
        ("我喜欢清淡一些的晚餐。", .57, .95, 2, "positive", 168, ["晚餐", "清淡", "偏好"]),
        ("蓝莓是我经常购买的水果。", .44, .91, 1, "positive", 96, ["蓝莓", "水果", "购买"]),
    ],
    "study": [
        ("我习惯早晨背英语单词。", .62, .96, 2, "positive", 48, ["英语单词", "早晨", "学习"]),
        ("阅读论文时，我会把难点留到晚上集中处理。", .64, .94, 2, "neutral", 72, ["论文", "晚上", "难点"]),
        ("周末复习数据库时，我主要看自己整理的 SQL 笔记。", .75, .99, 3, "positive", 18, ["数据库", "SQL笔记", "周末"]),
        ("我采用四十五分钟专注、十分钟休息的学习节奏。", .80, 1.00, 3, "positive", 12, ["四十五分钟", "十分钟", "专注"]),
        ("复杂概念我喜欢用手写思维导图梳理。", .61, .95, 2, "positive", 96, ["思维导图", "手写", "概念"]),
        ("课程视频我通常用一点五倍速播放。", .54, .93, 1, "neutral", 120, ["课程视频", "一点五倍速", "学习"]),
        ("需要长时间安静自习时，我会去市图书馆。", .78, .98, 3, "positive", 24, ["市图书馆", "安静", "自习"]),
        ("星期日晚上我会整理一周的学习笔记。", .60, .96, 2, "neutral", 72, ["星期日", "学习笔记", "整理"]),
        ("我目前学习中的薄弱项是概率统计。", .73, .97, 3, "negative", 36, ["概率统计", "薄弱项", "学习"]),
        ("通勤时我会听技术类音频课程。", .50, .92, 1, "positive", 168, ["通勤", "音频课程", "技术"]),
    ],
    "work": [
        ("上午九点到十一点是我处理深度工作的最佳时段。", .88, .99, 3, "positive", 12, ["深度工作", "九点到十一点", "上午"]),
        ("星期五下午我尽量不安排会议。", .69, .97, 2, "neutral", 48, ["星期五", "会议", "下午"]),
        ("我用 Todoist 管理工作任务。", .82, 1.00, 3, "positive", 18, ["Todoist", "任务管理", "工作"]),
        ("每周一早上我会列出本周工作目标。", .65, .96, 2, "positive", 72, ["周一", "工作目标", "计划"]),
        ("遇到紧急需求时，我要求先用文字确认范围和验收标准。", .87, .99, 3, "neutral", 8, ["紧急需求", "文字确认", "范围"]),
        ("团队沟通时，我偏好异步消息优先，再决定是否开会。", .72, .98, 3, "positive", 24, ["异步沟通", "会议", "团队"]),
        ("开始复杂任务前，我会先写一页执行计划。", .68, .96, 2, "positive", 36, ["执行计划", "复杂任务", "工作"]),
        ("我每天只在上午和傍晚各集中处理一次邮件。", .59, .94, 2, "neutral", 96, ["邮件", "上午", "傍晚"]),
        ("分配任务时，我最看重明确的验收条件和截止日期。", .79, .98, 3, "neutral", 48, ["验收条件", "截止日期", "任务"]),
        ("午休时间我会离开电脑，不处理工作消息。", .55, .95, 1, "positive", 120, ["午休", "工作消息", "边界"]),
    ],
    "software_tools": [
        ("我写前端和脚本时主要使用 VS Code。", .83, .99, 3, "positive", 24, ["VS Code", "前端", "脚本"]),
        ("开发 Java 项目时我习惯使用 IntelliJ IDEA。", .86, 1.00, 3, "positive", 12, ["IntelliJ IDEA", "Java", "开发"]),
        ("旧的 Python 工程偶尔还需要用 PyCharm 维护。", .43, .91, 1, "neutral", 168, ["PyCharm", "Python", "旧工程"]),
        ("探索数据并快速画图时，我会打开 Jupyter Notebook。", .74, .98, 3, "positive", 18, ["Jupyter Notebook", "数据探索", "画图"]),
        ("处理复杂 Git 冲突时我会用 GitKraken 辅助查看。", .57, .94, 2, "neutral", 72, ["GitKraken", "Git冲突", "工具"]),
        ("我把 Windows Terminal 作为默认终端。", .63, .96, 2, "positive", 48, ["Windows Terminal", "终端", "默认"]),
        ("工作浏览器主要使用 Microsoft Edge。", .51, .93, 1, "neutral", 96, ["Microsoft Edge", "浏览器", "工作"]),
        ("前端代码保存时会由 Prettier 自动格式化。", .60, .97, 2, "positive", 36, ["Prettier", "格式化", "前端"]),
        ("查看和维护 PostgreSQL 数据时，我常用 DBeaver。", .77, .99, 3, "positive", 18, ["DBeaver", "PostgreSQL", "数据库"]),
        ("调试 REST 接口时我通常使用 Postman。", .67, .97, 2, "positive", 72, ["Postman", "REST", "接口调试"]),
    ],
    "schedule_time": [
        ("工作日我通常早上七点起床。", .68, .98, 2, "neutral", 24, ["工作日", "七点", "起床"]),
        ("周末没有安排时，我通常睡到早上九点。", .73, .99, 3, "positive", 18, ["周末", "九点", "起床"]),
        ("我希望每天晚上十一点半前入睡。", .70, .96, 2, "positive", 48, ["十一点半", "入睡", "作息"]),
        ("星期一晚上安排力量训练。", .58, .94, 2, "positive", 96, ["星期一", "力量训练", "晚上"]),
        ("星期三下班后我会去买菜。", .54, .93, 1, "neutral", 120, ["星期三", "买菜", "下班后"]),
        ("每月第一天我会整理上月账单并更新预算。", .81, .99, 3, "neutral", 12, ["每月第一天", "账单", "预算"]),
        ("日程提醒一般设置为提前十五分钟。", .76, 1.00, 3, "neutral", 8, ["提前十五分钟", "提醒", "日程"]),
        ("我通常在晚上六点半吃晚饭。", .56, .95, 2, "positive", 72, ["六点半", "晚饭", "晚上"]),
        ("星期日傍晚我会规划下一周。", .64, .97, 2, "positive", 36, ["星期日", "下周计划", "傍晚"]),
        ("午睡超过二十分钟会让我醒来后头昏。", .49, .92, 1, "negative", 168, ["午睡", "二十分钟", "头昏"]),
    ],
    "relationships": [
        ("我的姐姐叫小雨，在上海做交互设计。", .88, 1.00, 3, "positive", 12, ["姐姐", "小雨", "上海"]),
        ("大学同学老周经常约我一起打羽毛球。", .75, .98, 3, "positive", 24, ["老周", "大学同学", "羽毛球"]),
        ("陈工是项目里的后端架构师。", .62, .96, 2, "neutral", 72, ["陈工", "后端架构师", "项目"]),
        ("妈妈的生日是五月十二日。", .92, 1.00, 3, "positive", 8, ["妈妈", "生日", "五月十二日"]),
        ("爸爸平时喜欢喝绿茶。", .60, .95, 2, "positive", 96, ["爸爸", "绿茶", "偏好"]),
        ("王老师是带我入门数据工程的导师。", .79, .99, 3, "positive", 18, ["王老师", "导师", "数据工程"]),
        ("小李负责前端代码评审。", .57, .94, 2, "neutral", 120, ["小李", "前端", "代码评审"]),
        ("朋友阿楠是一名摄影师。", .55, .93, 1, "positive", 168, ["阿楠", "摄影师", "朋友"]),
        ("表弟正在准备研究生考试。", .53, .95, 2, "neutral", 72, ["表弟", "研究生考试", "备考"]),
        ("邻居张叔偶尔帮我代收快递。", .42, .90, 1, "positive", 120, ["张叔", "邻居", "快递"]),
    ],
    "places": [
        ("清泰咖啡馆环境安静，适合和朋友聊天。", .76, .98, 3, "positive", 24, ["清泰咖啡馆", "安静", "聊天"]),
        ("市图书馆二楼靠窗区域最适合我学习。", .71, .97, 2, "positive", 48, ["市图书馆", "二楼", "学习"]),
        ("我平常在滨河公园跑步。", .78, .99, 3, "positive", 18, ["滨河公园", "跑步", "地点"]),
        ("我去城北体育馆打羽毛球。", .80, .99, 3, "positive", 12, ["城北体育馆", "羽毛球", "地点"]),
        ("我最喜欢秋天去杭州旅行。", .72, .98, 2, "positive", 36, ["杭州", "秋天", "旅行"]),
        ("旅行时我会避开过于拥挤的景点。", .59, .94, 2, "negative", 96, ["旅行", "拥挤", "景点"]),
        ("日常采购我多去附近的永辉超市。", .52, .93, 1, "neutral", 120, ["永辉超市", "采购", "附近"]),
        ("出差订酒店时，我优先选择地铁站附近。", .66, .96, 2, "neutral", 72, ["酒店", "地铁站", "出差"]),
        ("在咖啡馆我喜欢有插座的靠窗座位。", .50, .92, 1, "positive", 168, ["咖啡馆", "靠窗", "插座"]),
        ("小区东门的快递柜离我家最近。", .47, .91, 1, "neutral", 120, ["东门", "快递柜", "小区"]),
    ],
    "devices_environment": [
        ("我的主力笔记本是 ThinkPad X1 Carbon。", .84, 1.00, 3, "positive", 12, ["ThinkPad X1 Carbon", "笔记本", "主力设备"]),
        ("桌面键盘是一把矮轴机械键盘。", .65, .97, 2, "positive", 36, ["矮轴", "机械键盘", "桌面"]),
        ("我使用罗技 MX Master 3 鼠标。", .68, .98, 2, "positive", 24, ["罗技", "MX Master 3", "鼠标"]),
        ("主显示器是二十七英寸的 4K 屏幕。", .61, .96, 2, "positive", 72, ["二十七英寸", "4K", "显示器"]),
        ("系统和编辑器我都偏好深色模式。", .58, .95, 2, "positive", 96, ["深色模式", "系统", "编辑器"]),
        ("房间温度保持在二十四摄氏度时我最舒服。", .55, .94, 1, "positive", 120, ["二十四摄氏度", "房间", "温度"]),
        ("专注工作时我会戴降噪耳机。", .64, .97, 2, "positive", 48, ["降噪耳机", "专注", "工作"]),
        ("我的手机使用 Android 系统。", .63, .98, 2, "neutral", 72, ["Android", "手机", "系统"]),
        ("普通文档会同步到 OneDrive。", .57, .95, 2, "neutral", 96, ["OneDrive", "文档", "同步"]),
        ("重要资料每周日备份到移动硬盘。", .82, .99, 3, "neutral", 18, ["移动硬盘", "备份", "周日"]),
    ],
    "projects": [
        ("MindPet 是一个桌面端智能助手项目。", .76, .99, 3, "positive", 24, ["MindPet", "桌面端", "智能助手"]),
        ("MindPet 的长期记忆存储使用 PostgreSQL 和 pgvector。", .92, 1.00, 3, "neutral", 8, ["长期记忆", "PostgreSQL", "pgvector"]),
        ("MindPet 的短期会话记忆保存在 Redis。", .90, 1.00, 3, "neutral", 8, ["短期记忆", "Redis", "MindPet"]),
        ("MindPet 桌面界面基于 Electron 和 React。", .86, .99, 3, "positive", 12, ["Electron", "React", "桌面界面"]),
        ("MindPet 后端使用 Spring Boot。", .84, .99, 3, "neutral", 18, ["Spring Boot", "后端", "MindPet"]),
        ("当前项目最重要的目标是完成四种长期记忆检索模式评测。", .95, 1.00, 3, "positive", 6, ["当前目标", "四种检索模式", "评测"]),
        ("旧的天气机器人项目目前只偶尔维护。", .40, .90, 1, "neutral", 168, ["天气机器人", "旧项目", "维护"]),
        ("我以前做过一个用于整理发票的工具。", .38, .90, 1, "neutral", 168, ["发票工具", "旧项目", "整理"]),
        ("未来可能为 MindPet 增加知识图谱实验。", .58, .91, 2, "positive", 96, ["知识图谱", "未来", "实验"]),
        ("项目的重要设计决定会记录在 Markdown 文档中。", .64, .97, 2, "neutral", 48, ["Markdown", "设计决定", "文档"]),
    ],
    "personal_facts": [
        ("我现在常住南京。", .91, 1.00, 3, "neutral", 8, ["南京", "常住", "现在"]),
        ("我以前在苏州生活过三年，后来搬走了。", .49, .94, 2, "neutral", 168, ["苏州", "以前", "搬家"]),
        ("我平时戴眼镜。", .61, .98, 2, "neutral", 72, ["眼镜", "个人特征", "日常"]),
        ("我是左撇子。", .70, 1.00, 3, "neutral", 48, ["左撇子", "个人特征", "习惯"]),
        ("我的生日是十一月八日。", .88, 1.00, 3, "positive", 12, ["生日", "十一月八日", "个人信息"]),
        ("我每天主要乘地铁二号线上班。", .77, .99, 3, "neutral", 18, ["地铁二号线", "通勤", "上班"]),
        ("正式事务我更愿意通过电子邮件联系。", .56, .95, 2, "neutral", 96, ["电子邮件", "正式事务", "联系"]),
        ("我通常穿 L 码上衣。", .51, .94, 1, "neutral", 120, ["L码", "上衣", "尺码"]),
        ("我的紧急联系人是姐姐小雨。", .93, 1.00, 3, "neutral", 6, ["紧急联系人", "姐姐", "小雨"]),
        ("我的护照将在十二月到期。", .85, .98, 3, "negative", 24, ["护照", "十二月", "到期"]),
    ],
    "entertainment_hobbies": [
        ("现在通勤路上我主要听科技和历史类播客。", .78, .99, 3, "positive", 12, ["播客", "科技", "历史"]),
        ("我最喜欢的电影是《星际穿越》。", .74, .99, 3, "positive", 24, ["星际穿越", "电影", "最喜欢"]),
        ("睡前我喜欢读短篇科幻小说。", .66, .97, 2, "positive", 48, ["短篇科幻", "小说", "睡前"]),
        ("最近周末我主要坚持街头摄影。", .82, 1.00, 3, "positive", 8, ["街头摄影", "周末", "最近"]),
        ("聚会时我喜欢玩卡坦岛桌游。", .57, .95, 2, "positive", 96, ["卡坦岛", "桌游", "聚会"]),
        ("我不喜欢恐怖电影。", .64, .98, 2, "negative", 72, ["恐怖电影", "不喜欢", "电影"]),
        ("旅行时我愿意参观博物馆。", .60, .96, 2, "positive", 120, ["博物馆", "旅行", "参观"]),
        ("以前通勤时我常听流行音乐，现在已经很少听了。", .41, .93, 2, "neutral", 168, ["流行音乐", "以前", "通勤"]),
        ("以前周末常画速写，如今只是偶尔画一次。", .45, .94, 2, "positive", 120, ["速写", "周末", "偶尔"]),
        ("我喜欢自然纪录片，但不太看综艺节目。", .55, .95, 1, "positive", 96, ["自然纪录片", "综艺", "偏好"]),
    ],
}


QUERY_SPECS = [
    ("最喜欢的运动 羽毛球", ["m001"], "exact_keyword", "easy", "偏好关系和答案词均精确出现，ground truth 唯一。"),
    ("香菜", ["m012"], "exact_keyword", "easy", "直接命中饮食禁忌。"),
    ("四十五分钟", ["m024"], "exact_keyword", "easy", "中文时长原词命中。"),
    ("Todoist", ["m033"], "exact_keyword", "easy", "产品名精确命中。"),
    ("DBeaver", ["m049"], "exact_keyword", "easy", "工具名精确命中。"),
    ("提前十五分钟", ["m057"], "exact_keyword", "easy", "日程设置原词命中。"),
    ("王老师", ["m066"], "exact_keyword", "easy", "人物称谓精确命中。"),
    ("杭州", ["m075"], "exact_keyword", "easy", "地点名精确命中。"),
    ("我平时最爱做什么运动？", ["m001"], "semantic_paraphrase", "medium", "用‘最爱做’改写‘最喜欢的运动’。"),
    ("哪一种异国料理最合我的口味？", ["m011"], "semantic_paraphrase", "hard", "不出现日料或寿司字样。"),
    ("要长时间安静自习，我通常会去哪儿？", ["m027"], "semantic_paraphrase", "medium", "通过学习场景召回地点。"),
    ("一天里哪段时间最适合我处理高专注任务？", ["m031"], "semantic_paraphrase", "medium", "深度工作语义改写。"),
    ("写 Java 工程时我顺手的集成开发环境是哪款？", ["m042"], "semantic_paraphrase", "medium", "IDE 全称未在查询中出现。"),
    ("休息日没有安排时，我一般几点才起床？", ["m052"], "semantic_paraphrase", "medium", "周末作息语义改写。"),
    ("大学里认识的哪位朋友常约我一起打球？", ["m062"], "semantic_paraphrase", "hard", "人际关系与运动联合语义。"),
    ("想找家安静的小店聊天，我最可能选哪里？", ["m071"], "semantic_paraphrase", "hard", "避免直接出现咖啡馆名称。"),
    ("承担日常工作的那台笔记本是什么型号？", ["m081"], "semantic_paraphrase", "medium", "主力设备语义改写。"),
    ("这个助手把可长期召回的内容存在哪种技术里？", ["m092"], "semantic_paraphrase", "hard", "长期记忆技术栈的抽象问法。"),
    ("周末复习数据库时我会看什么？", ["m023"], "hybrid", "medium", "数据库关键词配合笔记语义。"),
    ("写前端代码时常用哪个编辑器？", ["m041"], "hybrid", "medium", "前端词面与工具语义结合。"),
    ("每月什么时候整理账单预算？", ["m056"], "hybrid", "medium", "账单预算关键词与具体时间。"),
    ("妈妈的生日具体是哪一天？", ["m064"], "hybrid", "easy", "人物、生日关键词与日期答案。"),
    ("我平常去哪里跑步？", ["m073"], "hybrid", "medium", "跑步词面与地点槽位。"),
    ("重要资料每周备份到什么设备？", ["m090"], "hybrid", "medium", "备份关键词与设备语义。"),
    ("MindPet 桌面界面用了哪些前端技术？", ["m094"], "hybrid", "medium", "项目名与技术栈共同约束。"),
    ("我每天主要乘什么交通工具上班？", ["m106"], "hybrid", "medium", "通勤场景与交通方式。"),
    ("碰到紧急需求时，我要求先用什么方式确认范围？", ["m035"], "multi_candidate", "hard", "m036 是沟通偏好的近邻干扰项。"),
    ("要探索数据并快速画图，我会打开哪个 Python 工具？", ["m044"], "multi_candidate", "hard", "m043 同属 Python 工具，是困难负例。"),
    ("我会分别去哪里跑步和打羽毛球？", ["m073", "m074"], "multi_candidate", "hard", "一个查询需要召回两个运动地点。"),
    ("桌面上常用的键盘和鼠标分别是什么？", ["m082", "m083"], "multi_candidate", "hard", "需要完整召回两种桌面外设。"),
    ("MindPet 分别用什么保存长期和短期记忆？", ["m092", "m093"], "multi_candidate", "hard", "需要区分并完整召回两类存储。"),
    ("我有哪些会亲自动手创作的周末爱好？", ["m114", "m119"], "multi_candidate", "hard", "街头摄影与偶尔速写均相关。"),
    ("我现在常住哪座城市？", ["m101"], "temporal_importance", "hard", "m102 的苏州是历史地点，不能覆盖当前南京。"),
    ("现在通勤路上主要听什么内容？", ["m111"], "temporal_importance", "hard", "m118 是已经很少发生的旧习惯。"),
    ("目前项目最重要的评测目标是什么？", ["m096"], "temporal_importance", "hard", "旧天气项目 m097 是主题近邻但非当前目标。"),
    ("现在周末主要坚持哪项创作活动？", ["m114"], "temporal_importance", "hard", "m119 是降低频率的旧习惯。"),
    ("我会演奏哪一种乐器？", [], "no_answer", "hard", "记忆中没有任何乐器事实。"),
    ("我养的宠物叫什么名字？", [], "no_answer", "hard", "记忆中没有宠物信息。"),
    ("我的汽车是什么品牌？", [], "no_answer", "hard", "记忆中没有汽车信息。"),
    ("我最喜欢的滑雪场是哪一家？", [], "no_answer", "hard", "记忆中没有滑雪场偏好。"),
]


def build_memories() -> list[dict]:
    memories = []
    for category, rows in MEMORY_GROUPS.items():
        for content, importance, confidence, layer, emotion, offset, tags in rows:
            memories.append({
                "memory_id": f"m{len(memories) + 1:03d}",
                "user_id": USER_ID,
                "content": content,
                "importance": importance,
                "confidence": confidence,
                "layer": layer,
                "emotion": emotion,
                "created_at_offset_hours": offset,
                "category": category,
                "tags": tags,
            })
    return memories


def build_queries() -> list[dict]:
    return [
        {
            "query_id": f"q{index:03d}",
            "query": query,
            "relevant_memory_ids": relevant_ids,
            "query_type": query_type,
            "difficulty": difficulty,
            "notes": notes,
        }
        for index, (query, relevant_ids, query_type, difficulty, notes)
        in enumerate(QUERY_SPECS, start=1)
    ]


def write_jsonl(path: Path, records: list[dict], force: bool) -> None:
    if path.exists() and path.stat().st_size > 0 and not force:
        raise FileExistsError(f"Refusing to overwrite non-empty file: {path}; use --force")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> None:
    default_dir = Path(__file__).resolve().parents[1] / "datasets" / "retrieval"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=default_dir)
    parser.add_argument("--force", action="store_true", help="overwrite non-empty output files")
    args = parser.parse_args()

    memories = build_memories()
    queries = build_queries()
    if len(memories) != 120 or len(queries) != 40:
        raise RuntimeError(f"Internal dataset count error: {len(memories)=}, {len(queries)=}")

    write_jsonl(args.output_dir / "memories.jsonl", memories, args.force)
    write_jsonl(args.output_dir / "queries.jsonl", queries, args.force)
    print(f"generated memories={len(memories)} queries={len(queries)} in {args.output_dir}")


if __name__ == "__main__":
    main()
