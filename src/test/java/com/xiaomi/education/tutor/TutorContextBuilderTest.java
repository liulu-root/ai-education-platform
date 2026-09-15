package com.xiaomi.education.tutor;

import com.xiaomi.education.ai.model.TokenEstimator;
import com.xiaomi.education.common.ApiException;
import com.xiaomi.education.knowledge.KnowledgeHit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TutorContextBuilderTest {
    private final TutorContextBuilder builder = new TutorContextBuilder(new TokenEstimator(), new ObjectMapper());

    @Test
    void dropsLowScoreEvidenceAndKeepsCitationsInActualPromptOrder() {
        var good = new KnowledgeHit("good", "doc", "course", "重要依据", .9, Map.of());
        var low = new KnowledgeHit("low", "doc", "course", "冗余资料".repeat(100), .1, Map.of());
        var result = builder.build("系统安全约束", "完整问题", List.of(low, good), Map.of(), "", List.of(), 150, false);
        assertThat(result.hits()).containsExactly(good);
        assertThat(result.prompt()).contains("[资料1] 重要依据", "完整问题").doesNotContain("冗余资料");
        assertThat(result.tokens()).isLessThanOrEqualTo(150);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void removesWholeOldTurnWithoutLeavingItsAssistantAnswer() {
        var history = List.of(new TutorRepository.Message(1, "USER", "旧问题".repeat(80)),
                new TutorRepository.Message(2, "ASSISTANT", "旧回答"),
                new TutorRepository.Message(3, "USER", "近期问题"),
                new TutorRepository.Message(4, "ASSISTANT", "近期回答"));
        var result = builder.build("系统", "当前问题", List.of(), Map.of(), "", history, 150, false);
        assertThat(result.prompt()).doesNotContain("旧问题", "旧回答").contains("近期问题", "近期回答");
        assertThat(result.historyCount()).isEqualTo(2);
    }

    @Test
    void neverTruncatesCurrentQuestionOrRemovesLastRequiredEvidence() {
        assertThatThrownBy(() -> builder.build("安全规则", "问题".repeat(200), List.of(), Map.of(), "", List.of(), 100, false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CONTEXT_INPUT_TOO_LARGE"));
        var hit = new KnowledgeHit("id", "doc", "course", "必要资料".repeat(200), .9, Map.of());
        assertThatThrownBy(() -> builder.build("安全规则", "问题", List.of(hit), Map.of(), "", List.of(), 100, false))
                .isInstanceOf(ApiException.class);
    }
}
