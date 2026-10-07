package service.v3;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class V3SensitiveAccountTest {
    @Test void bidirectionalSemanticWindowsReturnTheActualValueSpan() {
        String[][] inputs={
            {"test_9281 是我的测试账号","test_9281"},
            {"我的测试账号是 test_9281","test_9281"},
            {"qa_internal_47，这个值才是我的合成测试账号","qa_internal_47"},
            {"internal-882 是测试环境给我分配的登录账户","internal-882"},
            {"系统给的账号 ID 是 acct_88219","acct_88219"},
            {"acct_88219 是系统给我的账号 ID","acct_88219"},
            {"我在测试环境里用 demo_user_91 登录","demo_user_91"},
            {"（demo.login.72）上面这个值作为内部合成账号使用","demo.login.72"},
            {"测试登录标识：qa-user-24","qa-user-24"},
            {"我的 account id 是 internal.user.73，环境分配给我的","internal.user.73"}};
        for(String[] input:inputs) {
            assertThat(V3SensitiveAccount.identifiers(input[0])).as(input[0])
                .anySatisfy(span->{assertThat(span.value()).isEqualTo(input[1]);assertThat(input[0].substring(span.start(),span.end())).isEqualTo(input[1]);});
        }
        for(String input:new String[]{"ACCOUNT-SYNTHETIC-7318 是项目名","LOGIN-582 是变量","test_9281 是项目名","qa_internal_47 是代码变量","octocat 是我的 GitHub 用户名",
            "support 是公司的公开客服账号","我的游戏昵称是 NightFox","官方公开账号为 support_public"})
            assertThat(V3SensitiveAccount.containsIdentifier(input)).as(input).isFalse();
    }
    @Test void identifiersHaveCredentialContextRatherThanJustNames() {
        for(String input:new String[]{"登录账号为 demo_user_334","测试账号: test334","account identifier: app_user_334","user identifier is u334","账号：user334","账户是 a334"})
            assertThat(V3SensitiveAccount.containsIdentifier(input)).as(input).isTrue();
        for(String input:new String[]{"我的名字是王浩","我的用户名叫MoonCat","我们在开发账号权限管理项目","我学习账户安全","User experience research","The account settings changed"})
            assertThat(V3SensitiveAccount.containsIdentifier(input)).as(input).isFalse();
    }
    @Test void naturalAssignmentAndQuotedIdentifiersShareTheAccountSensitivityType() {
        for (String text : new String[]{
                "我的测试账号是 test_user_381", "登录账号是 demo-4821", "账户ID ACCT-000927",
                "这是内部测试账户 qa_bot_42", "测试系统分配给我的账号是 qa_2026_001",
                "我把临时账号设成了「sandbox_alpha」。", "我的用户账号叫 practice_guest。",
                "系统账号名为 bot_alpha", "internal account: internal_worker", "user_id = service_alpha",
                "account_id is acct_alpha", "login id: guest_alpha", "demo account named demo_alpha",
                "my account is qa", "internal account identifier is qa_bot", "登录账户是 王浩",
                "practice_guest才是我的测试账户", "worker_guest is my login account",
                "表单需要填写内部账户，我填写的是 worker_guest", "有人询问登录账号，我提交的是 guest_practice",
                "内部账户登记值为 [worker_alpha]", "登录账户的输入内容为 qa-team"}) {
            assertThat(V3SensitiveAccount.containsIdentifier(text)).as(text).isTrue();
            assertThat(V3SensitiveAccount.identifiers(text))
                .allSatisfy(span -> assertThat(span.sensitiveType()).isEqualTo("ACCOUNT_IDENTIFIER"));
        }
    }
    @Test void publicNamesAndLabelsAreNotPrivateLoginIdentifiers() {
        for (String text : new String[]{"我的 GitHub 用户名是 octocat", "项目负责人叫王浩",
                "我的游戏昵称是 NightFox", "公司公开客服账号是 support", "项目仓库 owner 是 mindpet-team",
                "公开账号是 USER-public-2042", "public account id is support_public",
                "这个项目名不是账户或密码", "它不是我的登录账号"}) {
            assertThat(V3SensitiveAccount.containsIdentifier(text)).as(text).isFalse();
        }
        assertThat(V3SensitiveAccount.containsIdentifier("公司公开客服账号是 support；我的登录账号是 private_guest")).isTrue();
        assertThat(V3SensitiveAccount.containsIdentifier("这是 public 测试账号 demo_guest")).isTrue();
    }
}
