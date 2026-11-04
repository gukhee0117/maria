package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.ai.ConditionDraft;
import com.app.maria.domain.stockpackage.ai.StockConditionParser;
import com.app.maria.domain.stockpackage.dto.request.StockConditionParseRequestDTO;
import com.app.maria.domain.stockpackage.dto.response.StockConditionParseResponseDTO;
import com.app.maria.domain.stockpackage.mapper.StockCandidateMapper;
import com.app.maria.domain.stockpackage.type.CandidateSort;
import com.app.maria.domain.stockpackage.type.ParseStatus;
import com.app.maria.domain.stockpackage.type.SortDirection;
import com.app.maria.domain.stockpackage.type.StockPackageSentence;
import com.app.maria.domain.stockpackage.vo.StockConditionParseVO;
import com.app.maria.domain.stockpackage.vo.StockConditionParseVO.Filter;
import com.app.maria.global.clock.service.BusinessClockService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockConditionServiceImpl implements StockConditionService {
    private final StockConditionParser parser;
    private final StockCandidateMapper candidateMapper;
    private final BusinessClockService businessClockService;

    @Override
    public StockConditionParseResponseDTO parse(StockConditionParseRequestDTO request) {
        String text = request.text().trim();
        if (AdviceRequestDetector.isAdviceRequest(text)) {
            return response(ParseStatus.ADVICE_REFUSED, null, false, adviceRefusal());
        }

        List<String> themeNames = candidateMapper.selectThemeNames();
        Optional<ConditionDraft> draft = parser.parse(text, themeNames);
        if (draft.isEmpty()) {
            return response(
                    ParseStatus.UNAVAILABLE,
                    null,
                    false,
                    List.of(StockPackageSentence.CONDITION_UNAVAILABLE.render(Map.of())));
        }
        if (draft.get().intent() == ConditionDraft.Intent.ADVICE_REQUEST) {
            return response(ParseStatus.ADVICE_REFUSED, null, false, adviceRefusal());
        }

        StockConditionNormalizer.Normalized normalized =
                StockConditionNormalizer.normalize(draft.get(), themeNames);
        if (draft.get().intent() != ConditionDraft.Intent.FILTER || normalized.filter().isEmpty()) {
            return response(
                    ParseStatus.NOT_UNDERSTOOD,
                    null,
                    false,
                    List.of(StockPackageSentence.CONDITION_NOT_UNDERSTOOD.render(Map.of())));
        }
        return response(
                ParseStatus.PARSED,
                normalized.filter(),
                normalized.partial(),
                parsedMessages(normalized.filter(), normalized.partial()));
    }

    private List<String> adviceRefusal() {
        return List.of(
                StockPackageSentence.CONDITION_ADVICE_REFUSED.render(Map.of()),
                StockPackageSentence.CONDITION_ADVICE_ALTERNATIVE.render(Map.of()));
    }

    /** 검증을 통과한 값만 문장은행에 끼워 넣는다. */
    private List<String> parsedMessages(Filter f, boolean partial) {
        List<String> lines = new ArrayList<>();
        lines.add(StockPackageSentence.CONDITION_PARSED.render(Map.of()));
        if (!f.productTypes().isEmpty()) {
            lines.add(
                    StockPackageSentence.CONDITION_TYPES.render(
                            Map.of(
                                    "types",
                                    f.productTypes().stream()
                                            .map(StockConditionServiceImpl::typeLabel)
                                            .collect(Collectors.joining(", ")))));
        }
        if (!f.themes().isEmpty()) {
            lines.add(
                    StockPackageSentence.CONDITION_THEMES.render(
                            Map.of("themes", String.join(", ", f.themes()))));
        }
        if (f.minGrade() != null || f.maxGrade() != null) {
            boolean exact = f.minGrade() != null && f.minGrade().equals(f.maxGrade());
            lines.add(
                    (exact
                                    ? StockPackageSentence.CONDITION_GRADE_EXACT
                                    : StockPackageSentence.CONDITION_GRADE)
                            .render(
                                    Map.of(
                                            "range",
                                            exact
                                                    ? f.minGrade() + "등급"
                                                    : range(f.minGrade(), f.maxGrade(), "등급"))));
        }
        if (f.itemCountMin() != null || f.itemCountMax() != null) {
            boolean exact = f.itemCountMin() != null && f.itemCountMin().equals(f.itemCountMax());
            lines.add(
                    (exact
                                    ? StockPackageSentence.CONDITION_COUNT_EXACT
                                    : StockPackageSentence.CONDITION_COUNT)
                            .render(
                                    Map.of(
                                            "range",
                                            exact
                                                    ? f.itemCountMin() + "종목"
                                                    : range(
                                                            f.itemCountMin(),
                                                            f.itemCountMax(),
                                                            "종목"))));
        }
        if (f.sort() != null || f.direction() != null) {
            lines.add(
                    StockPackageSentence.CONDITION_SORT.render(
                            Map.of("sortLabel", sortLabel(f.sort(), f.direction()))));
        }
        if (partial) {
            lines.add(StockPackageSentence.CONDITION_PARTIAL.render(Map.of()));
        }
        lines.add(StockPackageSentence.CONDITION_APPLY_HINT.render(Map.of()));
        return List.copyOf(lines);
    }

    private static String typeLabel(Type type) {
        return type == Type.STOCK ? "주식" : "펀드";
    }

    private static String range(Integer min, Integer max, String unit) {
        if (min != null && max != null) {
            return min + unit + "부터 " + max + unit + "까지";
        }
        return min != null ? min + unit + "부터" : max + unit + "까지";
    }

    private static String sortLabel(CandidateSort sort, SortDirection direction) {
        CandidateSort s = sort == null ? CandidateSort.NAME : sort;
        boolean asc = direction != SortDirection.DESC;
        return switch (s) {
            case NAME -> asc ? "종목명 가나다순" : "종목명 가나다 역순";
            case RISK_GRADE -> asc ? "위험등급 1등급부터" : "위험등급 6등급부터";
            case VOLATILITY -> asc ? "연 변동성이 작은 값부터" : "연 변동성이 큰 값부터";
        };
    }

    private StockConditionParseResponseDTO response(
            ParseStatus status, Filter filter, boolean partial, List<String> messages) {
        return new StockConditionParseResponseDTO(
                StockConditionParseVO.builder()
                        .status(status)
                        .filter(filter)
                        .partial(partial)
                        .messages(messages)
                        .disclaimer(StockPackageCheckServiceImpl.DISCLAIMER)
                        .checkedAt(businessClockService.now())
                        .build());
    }
}
