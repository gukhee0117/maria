package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.service.KkiruMention;
import com.app.maria.domain.community.type.KkiruStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 끼루 호출 하나를 끝까지 처리한다. 트랜잭션을 걸지 않는 조정자이고, DB 작업은 {@link KkiruReplyWriter}가 짧게 맡는다(도구·LLM 호출 동안 잠금을
 * 잡지 않기 위해서다). 같은 호출을 두 번 처리해도 답글은 하나만 생긴다.
 *
 * <p>흐름: 질문 읽기 → 질문 다듬기 → 도구로 근거 모으기 → 답 쓰기(LLM, 실패하면 템플릿) → 저장. 근거가 없으면 NO_SOURCE, 모든 도구가 오류이거나 답을
 * 못 만들면 FAILED다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KkiruReplyService {
    private final KkiruReplyWriter writer;
    private final EvidenceCollector collector;
    private final KkiruAnswerWriter answerWriter;

    public void reply(Long postId, Long commentId) {
        KkiruQuestion loaded = writer.loadPendingQuestion(postId, commentId);
        if (loaded == null) {
            return;
        }
        try {
            answer(postId, commentId, loaded);
        } catch (RuntimeException e) {
            log.warn("[KKIRU_REPLY_FAILED] postId={} commentId={}", postId, commentId, e);
            markFailedQuietly(postId, commentId);
        }
    }

    private void answer(Long postId, Long commentId, KkiruQuestion loaded) {
        long start = System.nanoTime();
        KkiruQuestion question =
                new KkiruQuestion(
                        postId, commentId, loaded.ticker(), KkiruMention.strip(loaded.text()));
        EvidenceCollector.Collected collected = collector.collect(question);
        long toolMs = elapsedMillis(start);

        if (collected.evidence().isEmpty()) {
            // 오류가 섞여 있으면 "자료 없음"이라고 말할 수 없다: 다시 물을 수 있게 FAILED
            KkiruStatus status =
                    collected.failedTools() > 0 ? KkiruStatus.FAILED : KkiruStatus.NO_SOURCE;
            if (status == KkiruStatus.FAILED) {
                writer.markFailed(postId, commentId);
            } else {
                writer.markNoSource(postId, commentId);
            }
            logResult(status, false, collected, toolMs, 0, start);
            return;
        }

        long llmStart = System.nanoTime();
        Optional<KkiruAnswerWriter.Written> written =
                answerWriter.write(
                        question.text(), collected.evidence(), !collected.defaultBundle());
        long llmMs = elapsedMillis(llmStart);
        if (written.isEmpty()) {
            writer.markFailed(postId, commentId);
            logResult(KkiruStatus.FAILED, false, collected, toolMs, llmMs, start);
            return;
        }
        if (written.get().declined()) {
            // 기본 묶음 근거로는 질문에 답할 수 없다고 LLM이 판단했다: 엉뚱한 근거를 늘어놓지 않는다.
            writer.markNoSource(postId, commentId);
            logResult(KkiruStatus.NO_SOURCE, false, collected, toolMs, llmMs, start);
            return;
        }
        KkiruStatus status = writer.finish(postId, commentId, written.get().answer());
        logResult(status, written.get().fallback(), collected, toolMs, llmMs, start);
    }

    private void markFailedQuietly(Long postId, Long commentId) {
        try {
            writer.markFailed(postId, commentId);
        } catch (RuntimeException markFailure) {
            log.error(
                    "[KKIRU_MARK_FAILED_ERROR] postId={} commentId={}",
                    postId,
                    commentId,
                    markFailure);
        }
    }

    // 질문 본문과 키는 남기지 않는다.
    private static void logResult(
            KkiruStatus status,
            boolean fallback,
            EvidenceCollector.Collected collected,
            long toolMs,
            long llmMs,
            long start) {
        log.info(
                "[KKIRU_ANSWER] status={} fallback={} tools={} evidence={} toolMs={} llmMs={} totalMs={}",
                status,
                fallback,
                String.join(",", collected.toolNames()),
                collected.evidence().size(),
                toolMs,
                llmMs,
                elapsedMillis(start));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
