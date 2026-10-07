package service.v3;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class V3TemporalLifecycleTest {
    @Test
    void eventBoundedResidenceAndFutureMoveAreSeparate() {
        var bounds = V3TemporalLifecycle.bounds("最近我先住North Lodge，等South Court的钥匙拿到以后就搬过去。");
        assertThat(bounds).hasSize(1);
        var b = bounds.get(0);
        assertThat(b.currentEndpoint()).isEqualTo("North Lodge");
        assertThat(b.endCondition()).isEqualTo("South Court的钥匙拿到");
        assertThat(V3TemporalLifecycle.classify(b,"user","North Lodge",List.of(),"related_to","LIVES_AT","UNKNOWN"))
            .isEqualTo("BOUNDED");
        assertThat(V3TemporalLifecycle.classify(b,"user","搬到 South Court",List.of(),"plans","HAS_GOAL","UNKNOWN"))
            .isEqualTo("FUTURE");
    }

    @Test
    void loanedToolUntilRepairAndSwitchBackAreSeparate() {
        var b = V3TemporalLifecycle.bounds("目前我先使用Loan Laptop，等Work Laptop修好后换回Work Laptop。").get(0);
        assertThat(b.currentEndpoint()).isEqualTo("Loan Laptop");
        assertThat(V3TemporalLifecycle.classify(b,"user","Loan Laptop",List.of(),"uses","USES","UNKNOWN"))
            .isEqualTo("BOUNDED");
        assertThat(V3TemporalLifecycle.classify(b,"user","Work Laptop",List.of(),"uses","USES","PERMANENT"))
            .isEqualTo("FUTURE");
    }

    @Test
    void temporaryWorkWithExplicitEndEventIsBounded() {
        var b = V3TemporalLifecycle.bounds("这段时间我负责Migration，项目完成后转去Reporting。").get(0);
        assertThat(b.semanticPredicate()).isEqualTo("TEMPORARILY_WORKS_ON");
        assertThat(V3TemporalLifecycle.classify(b,"user","Migration",List.of(),"works_on","WORKS_ON","PERMANENT"))
            .isEqualTo("BOUNDED");
        assertThat(V3TemporalLifecycle.classify(b,"user","Reporting",List.of(),"plans","PLANS_TO_WORK_ON","UNKNOWN"))
            .isEqualTo("FUTURE");
    }

    @Test
    void ordinaryPermanentFactsRemainUntouched() {
        assertThat(V3TemporalLifecycle.bounds("我长期住North Lodge，每天使用Work Laptop。")).isEmpty();
    }

    @Test
    void futurePlanAloneDoesNotInventCurrentState() {
        assertThat(V3TemporalLifecycle.bounds("等South Court交付以后我就搬过去。")).isEmpty();
        assertThat(V3TemporalLifecycle.bounds("我计划以后搬到South Court。")).isEmpty();
    }

    @Test
    void hypotheticalAndThirdPartyStatementsDoNotBecomeUserLifecycle() {
        assertThat(V3TemporalLifecycle.bounds("假设我最近先住North Lodge，等South Court交付后搬过去。")).isEmpty();
        assertThat(V3TemporalLifecycle.bounds("最近邻居先住North Lodge，等South Court交付后搬过去。")).isEmpty();
    }

    @Test
    void englishEventBoundsUseOnlyLiteralCurrentEvidence() {
        var b = V3TemporalLifecycle.bounds("For now I use Loan Laptop until Work Laptop is repaired, then I switch back to Work Laptop.").get(0);
        assertThat(b.currentEndpoint()).isEqualTo("Loan Laptop");
        assertThat(V3TemporalLifecycle.literalEndpoint(b)).isTrue();
        assertThat(V3FactResolver.resolve("NEW","FUTURE","","").temporalStatus()).isEqualTo("FUTURE");
    }

    @Test
    void purposeTextCannotBeFreelyPromotedAsARecoveredEndpoint() {
        var b = V3TemporalLifecycle.bounds("现在我先用某个工具写报告，等电脑修好后换回电脑。").get(0);
        assertThat(V3TemporalLifecycle.literalEndpoint(b)).isFalse();
    }

    @Test
    void eventPeriodAndRepairPreludeDoNotHideTheCurrentState() {
        var residence=V3TemporalLifecycle.bounds("检修期间我住North Lodge，检修结束后搬回South Court。").get(0);
        assertThat(residence.currentEndpoint()).isEqualTo("North Lodge");
        var repair=V3TemporalLifecycle.bounds("电脑送修了，这两天我临时用Loan Laptop，修好后归还。").get(0);
        assertThat(repair.currentEndpoint()).isEqualTo("Loan Laptop");
        var duration=V3TemporalLifecycle.bounds("设备坏了，我临时用Loan Laptop一天，设备修好后换回去。").get(0);
        assertThat(duration.currentEndpoint()).isEqualTo("Loan Laptop");
    }
}
