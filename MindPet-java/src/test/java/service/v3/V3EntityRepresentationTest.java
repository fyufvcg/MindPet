package service.v3;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class V3EntityRepresentationTest {
    @Test
    void projectsCategoryNotBrandAndProtectsLanguagesAndDatabases() {
        assertThat(V3EntityRepresentation.type("technology", "APPLICATION", "communication application")).isEqualTo("tool");
        assertThat(V3EntityRepresentation.type("tool", "PROGRAMMING_LANGUAGE", "language used for development")).isEqualTo("technology");
        assertThat(V3EntityRepresentation.type("technology", "", "用户长期用它收邮件")).isEqualTo("tool");
        assertThat(V3EntityRepresentation.type("technology", "", "用户长期使用的代码编辑器")).isEqualTo("tool");
        assertThat(V3EntityRepresentation.type("technology", "", "编程语言，用于开发收邮件的服务")).isEqualTo("technology");
        assertThat(V3EntityRepresentation.type("technology", "", "主要数据库")).isEqualTo("technology");
        assertThat(V3EntityRepresentation.type("person", "UNSPECIFIED", "工具团队成员")).isEqualTo("person");
        assertThat(V3EntityRepresentation.type("project", "APPLICATION", "ongoing application project")).isEqualTo("project");
        assertThat(V3EntityRepresentation.type("goal", "NATURAL_LANGUAGE", "practice a language")).isEqualTo("goal");
        assertThat(V3EntityRepresentation.type("event", "APPLICATION", "scheduled application review")).isEqualTo("event");
    }

    @Test
    void canonicalEventKeepsSourceVerbAndAliasesRetainScheduleParameters() {
        assertThat(V3EntityRepresentation.eventName("明晚的演出", "我明晚去看一场演出。")).isEqualTo("明晚看演出");
        assertThat(V3EntityRepresentation.eventName("今晚的活动", "他还没有说明活动内容。")).isEqualTo("今晚的活动");
        assertThat(V3EntityRepresentation.eventName("海岸艺术节", "我明天去参加海岸艺术节。")).isEqualTo("海岸艺术节");
        assertThat(V3EntityCanonicalizer.canonicalName("每周五下午练琴", "event")).isEqualTo("每周五练琴");
        assertThat(V3EntityCanonicalizer.canonicalName("未来三周每天康复训练", "event")).isEqualTo("康复训练");
    }
}
