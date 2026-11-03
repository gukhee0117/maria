package com.app.maria.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommunityPostEntityTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 14, 48);

    private static CommunityPostEntity post(KkiruStatus kiru) {
        return CommunityPostEntity.write(7L, "000660", "HBM 증설 기사 나왔던데요", kiru, NOW);
    }

    @Test
    @DisplayName("글을 쓰면 보이는 상태이고 본문 앞뒤 공백은 잘린다")
    void write() {
        CommunityPostEntity post = CommunityPostEntity.write(7L, null, "  안녕하세요  ", null, NOW);

        assertThat(post.getContent()).isEqualTo("안녕하세요");
        assertThat(post.getStatus()).isEqualTo(CommunityStatus.VISIBLE);
        assertThat(post.getTicker()).isNull();
        assertThat(post.getEditedAt()).isNull();
        assertThat(post.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("본문이 비었거나 공백뿐이거나 500자를 넘으면 거부한다")
    void contentLimits() {
        assertError(
                () -> CommunityPostEntity.write(7L, null, "   ", null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(
                () -> CommunityPostEntity.write(7L, null, null, null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(
                () -> CommunityPostEntity.write(7L, null, "가".repeat(501), null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertThat(CommunityPostEntity.write(7L, null, "가".repeat(500), null, NOW).getContent())
                .hasSize(500);
    }

    @Test
    @DisplayName("이모지처럼 두 칸을 차지하는 문자도 한 글자로 센다")
    void countsCodePoints() {
        String fiveHundredEmoji = "😀".repeat(500);

        assertThat(CommunityPostEntity.write(7L, null, fiveHundredEmoji, null, NOW).getContent())
                .isEqualTo(fiveHundredEmoji);
        assertError(
                () -> CommunityPostEntity.write(7L, null, "😀".repeat(501), null, NOW),
                ErrorType.COMMUNITY_CONTENT_INVALID);
    }

    @Test
    @DisplayName("수정하면 본문이 바뀌고 수정 시각이 남는다")
    void edit() {
        CommunityPostEntity post = post(null);
        LocalDateTime later = NOW.plusMinutes(5);

        post.edit("고친 내용", later);

        assertThat(post.getContent()).isEqualTo("고친 내용");
        assertThat(post.getEditedAt()).isEqualTo(later);
        assertThat(post.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("끼루가 이미 답한 글은 수정할 수 없고 본문도 그대로다")
    void cannotEditAnswered() {
        CommunityPostEntity post = post(KkiruStatus.PENDING);
        post.moveKkiruStatus(KkiruStatus.ANSWERED);

        assertThat(post.canEdit()).isFalse();
        assertError(() -> post.edit("바꾸기", NOW), ErrorType.COMMUNITY_POST_NOT_EDITABLE);
        assertThat(post.getContent()).isEqualTo("HBM 증설 기사 나왔던데요");
        assertThat(post.getEditedAt()).isNull();
    }

    @Test
    @DisplayName("답을 만드는 중(PENDING)인 글은 수정할 수 없고, 답을 못 받은 글(FAILED·NO_SOURCE·범위 밖)은 수정할 수 있다")
    void canEditOnlyWhenNoAnswerIsComing() {
        CommunityPostEntity pending = post(KkiruStatus.PENDING);
        assertThat(pending.canEdit()).isFalse();
        pending.moveKkiruStatus(KkiruStatus.FAILED);
        assertThat(pending.canEdit()).isTrue();

        CommunityPostEntity noSource = post(KkiruStatus.PENDING);
        noSource.moveKkiruStatus(KkiruStatus.NO_SOURCE);
        assertThat(noSource.canEdit()).isTrue();
        assertThat(post(KkiruStatus.OUT_OF_SCOPE).canEdit()).isTrue();
        assertThat(post(null).canEdit()).isTrue();
    }

    @Test
    @DisplayName("숨김·삭제된 글은 수정할 수 없다")
    void cannotEditHiddenOrDeleted() {
        CommunityPostEntity hidden = post(null);
        hidden.hide();
        assertError(() -> hidden.edit("x", NOW), ErrorType.COMMUNITY_POST_NOT_EDITABLE);

        CommunityPostEntity deleted = post(null);
        deleted.markDeleted();
        assertError(() -> deleted.edit("x", NOW), ErrorType.COMMUNITY_POST_NOT_EDITABLE);
    }

    @Test
    @DisplayName("숨김과 복구: 이미 숨긴 글을 또 숨겨도 안전하고, 숨김이 아니면 복구할 수 없다")
    void hideRestore() {
        CommunityPostEntity post = post(null);

        assertError(post::restore, ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        post.hide();
        post.hide();
        assertThat(post.getStatus()).isEqualTo(CommunityStatus.HIDDEN);
        post.restore();
        assertThat(post.getStatus()).isEqualTo(CommunityStatus.VISIBLE);
    }

    @Test
    @DisplayName("삭제된 글은 숨길 수도 되살릴 수도 없다")
    void deletedIsFinal() {
        CommunityPostEntity post = post(null);
        post.markDeleted();

        assertError(post::hide, ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        assertError(post::restore, ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        assertThat(post.getStatus()).isEqualTo(CommunityStatus.DELETED);
    }

    @Test
    @DisplayName("끼루 상태는 허용된 순서로만 바뀐다")
    void kkiruTransitions() {
        CommunityPostEntity noCall = post(null);
        assertError(
                () -> noCall.moveKkiruStatus(KkiruStatus.ANSWERED),
                ErrorType.COMMUNITY_KKIRU_STATUS_INVALID);

        CommunityPostEntity post = post(KkiruStatus.PENDING);
        post.moveKkiruStatus(KkiruStatus.ANSWERED);
        assertError(
                () -> post.moveKkiruStatus(KkiruStatus.PENDING),
                ErrorType.COMMUNITY_KKIRU_STATUS_INVALID);
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }
}
