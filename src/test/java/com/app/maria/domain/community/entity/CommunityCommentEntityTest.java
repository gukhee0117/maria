package com.app.maria.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommunityCommentEntityTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 14, 35);

    @Test
    @DisplayName("고객 댓글은 작성자가 있고 AI가 아니다")
    void customerComment() {
        CommunityCommentEntity comment =
                CommunityCommentEntity.ofCustomer(1L, null, 7L, "저도 봤어요", null, NOW);

        assertThat(comment.getAuthorType()).isEqualTo(AuthorType.CUSTOMER);
        assertThat(comment.getCustomerId()).isEqualTo(7L);
        assertThat(comment.getParentCommentId()).isNull();
        assertThat(comment.getSourcesJson()).isNull();
        assertThat(comment.getStatus()).isEqualTo(CommunityStatus.VISIBLE);
    }

    @Test
    @DisplayName("답하는 대상 댓글 id가 그대로 남는다")
    void replyTarget() {
        CommunityCommentEntity comment =
                CommunityCommentEntity.ofCustomer(1L, 42L, 7L, "두 배면 꽤 많네요", null, NOW);

        assertThat(comment.getParentCommentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("끼루 댓글은 AI이고 작성자가 없으며 근거를 가진다")
    void aiComment() {
        CommunityCommentEntity comment =
                CommunityCommentEntity.ofAi(1L, 42L, "공시 기준으로…", "[{\"type\":\"DART\"}]", NOW);

        assertThat(comment.getAuthorType()).isEqualTo(AuthorType.AI);
        assertThat(comment.getCustomerId()).isNull();
        assertThat(comment.getParentCommentId()).isEqualTo(42L);
        assertThat(comment.getSourcesJson()).contains("DART");
    }

    @Test
    @DisplayName("글 본문에서 호출한 끼루 답글은 parent가 비어 있다")
    void aiCommentForPostCall() {
        assertThat(CommunityCommentEntity.ofAi(1L, null, "답", null, NOW).getParentCommentId())
                .isNull();
    }

    @Test
    @DisplayName("댓글 본문도 500자를 넘거나 비면 거부한다")
    void contentLimits() {
        assertError(
                () -> CommunityCommentEntity.ofCustomer(1L, null, 7L, " ", null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(
                () -> CommunityCommentEntity.ofCustomer(1L, null, 7L, "가".repeat(501), null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(
                () -> CommunityCommentEntity.ofAi(1L, null, "", null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
    }

    @Test
    @DisplayName("댓글의 숨김·복구·삭제는 글과 같은 규칙이다")
    void statusMoves() {
        CommunityCommentEntity comment =
                CommunityCommentEntity.ofCustomer(1L, null, 7L, "본문", null, NOW);

        assertError(comment::restore, ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        comment.hide();
        assertThat(comment.getStatus()).isEqualTo(CommunityStatus.HIDDEN);
        comment.restore();
        comment.markDeleted();
        assertError(comment::hide, ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
    }

    @Test
    @DisplayName("댓글에서 끼루를 부르면 PENDING으로 시작해 결과로 끝난다")
    void kkiruOnComment() {
        CommunityCommentEntity comment =
                CommunityCommentEntity.ofCustomer(
                        1L, null, 7L, "@끼루 공시 있나요", KkiruStatus.PENDING, NOW);

        comment.moveKkiruStatus(KkiruStatus.NO_SOURCE);

        assertThat(comment.getKkiruStatus()).isEqualTo(KkiruStatus.NO_SOURCE);
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }
}
