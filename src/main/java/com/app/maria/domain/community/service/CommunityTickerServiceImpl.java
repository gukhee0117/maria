package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.TickerRankResponseDTO;
import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import com.app.maria.domain.community.kkiru.TrendReasonService;
import com.app.maria.domain.community.mapper.CommunityTickerMapper;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 종목 드롭박스와 "많이 이야기하는 종목" 순위. */
@Service
@RequiredArgsConstructor
public class CommunityTickerServiceImpl implements CommunityTickerService {
    // 순위에 오를 수 있는 종목 수의 넉넉한 상한(글이 있는 종목 전부를 가져와 합산한 뒤 size로 자른다).
    private static final int MAX_TICKERS = 1000;

    private final CommunityTickerMapper tickerMapper;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final TrendReasonService trendReasons;
    private final BusinessClockService clock;

    @Override
    public List<TickerResponseDTO> list() {
        return tickerMapper.selectAllTickers();
    }

    /** 종목 마스터에 없는 종목코드면 COMMUNITY_TICKER_NOT_FOUND. */
    @Override
    public void requireExists(String ticker) {
        if (!tickerMapper.existsTicker(ticker)) {
            throw new AppException(ErrorType.COMMUNITY_TICKER_NOT_FOUND, ticker);
        }
    }

    /**
     * 업무일 하루 동안 화제인 종목. 점수는 오늘 글 수 + 오늘 이용자 댓글 수이고, 같으면 종목코드 순이다. 시세가 없으니 등락률은 싣지 않는다. 한 줄 이유는 캐시에
     * 있으면 싣고 없으면 null로 응답하면서 비동기로 만들기 시작한다(조회는 LLM을 기다리지 않는다).
     */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public List<TickerRankResponseDTO> ranking(int size) {
        LocalDateTime from = clock.now().toLocalDate().atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        Map<String, long[]> counts = new HashMap<>(); // ticker -> {글 수, 댓글 수}
        postRepository
                .rankTickers(from, to, PageRequest.of(0, MAX_TICKERS))
                .forEach(
                        c ->
                                counts.computeIfAbsent(c.getTicker(), k -> new long[2])[0] =
                                        c.getPostCount());
        commentRepository
                .countTickerComments(from, to)
                .forEach(
                        c ->
                                counts.computeIfAbsent(c.getTicker(), k -> new long[2])[1] =
                                        c.getCommentCount());
        List<String> tickers =
                counts.entrySet().stream()
                        .sorted(
                                Comparator.comparingLong(
                                                (Map.Entry<String, long[]> e) ->
                                                        e.getValue()[0] + e.getValue()[1])
                                        .reversed()
                                        .thenComparing(Map.Entry::getKey))
                        .limit(size)
                        .map(Map.Entry::getKey)
                        .toList();
        if (tickers.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        tickerMapper
                .selectTickersByCodes(tickers)
                .forEach(t -> names.putIfAbsent(t.getTicker(), t.getName()));
        List<TickerRankResponseDTO> ranking = new ArrayList<>();
        int rank = 1;
        for (String ticker : tickers) {
            long[] count = counts.get(ticker);
            ranking.add(
                    new TickerRankResponseDTO(
                            rank++,
                            ticker,
                            names.get(ticker),
                            count[0],
                            count[1],
                            trendReasons.reasonOrRequest(ticker)));
        }
        return ranking;
    }
}
