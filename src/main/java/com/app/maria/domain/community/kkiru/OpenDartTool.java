package com.app.maria.domain.community.kkiru;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * OpenDART를 쓰는 도구의 공통 뼈대. 키가 비어 있으면 후보에서 빠지고(오류가 아니라 "도구 없음"), 종목이 대응표에 없으면(해외·비상장) 빈 결과다. 기준 날짜는
 * 업무시계로 잡는다.
 */
@RequiredArgsConstructor
abstract class OpenDartTool implements EvidenceTool {
    static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    protected final OpenDartClient client;
    protected final BusinessClockService clock;

    /** 질문에 이 단어가 하나라도 있으면 후보다(정규화한 질문 기준). */
    protected abstract List<String> words();

    /** 대응표에 있는 국내 상장사의 근거를 모은다. 자료가 없으면 빈 목록. */
    protected abstract List<Evidence> collectFor(KkiruQuestion question, OpenDartClient.Corp corp);

    @Override
    public boolean isRelevant(KkiruQuestion question) {
        return client.isEnabled() && QuestionWords.containsAny(question, words());
    }

    @Override
    public List<Evidence> collect(KkiruQuestion question) {
        if (!client.isEnabled()) {
            return List.of();
        }
        Optional<OpenDartClient.Corp> corp = client.corp(question.ticker());
        return corp.isEmpty() ? List.of() : collectFor(question, corp.get());
    }
}
