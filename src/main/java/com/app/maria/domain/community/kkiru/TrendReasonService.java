package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 오늘 화제인 종목의 "한 줄 이유"(설계 후속 문서 3절). 순위 조회가 LLM을 기다리지 않도록, 캐시에 있으면 돌려주고 없으면 비동기로 만든다. 같은 종목은 동시에 한
 * 번만 만들고(진행 중 표시), 결과는 업무일·종목별로 10분(실제 시각) 캐시한다. 만들 수 없는 경우(이야기가 2개 미만, LLM 오류, 검증 탈락)도 캐시해서 같은 실패를
 * 매 조회마다 되풀이하지 않는다(오류는 2분만).
 *
 * <p>이용자 글을 LLM에 넣으므로 시스템 프롬프트가 지시문을 무시하게 하고, 결과는 8b의 검증기로 다시 확인한다(의견 표현·금칙어·근거 밖 숫자).
 */
@Slf4j
@Component
public class TrendReasonService {
    static final int MAX_ITEMS = 20;
    static final int MIN_ITEMS = 2;
    static final int ITEM_LENGTH = 150;
    static final int REASON_MAX_LENGTH = 60;
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration FAILURE_TTL = Duration.ofMinutes(2);

    /** reason이 null이면 "만들 수 없었다"는 결과도 캐시한다. */
    private record Entry(String reason, Instant expiresAt) {}

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final KkiruAnswerValidator validator;
    private final Executor executor;
    private final Clock clock;
    private final BusinessClockService businessClock;
    private final String systemPrompt;

    @Value("${maria.community.trend-reason.enabled:true}")
    private boolean enabled = true;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TrendReasonService(
            CommunityPostRepository postRepository,
            CommunityCommentRepository commentRepository,
            @Qualifier("communityKkiruChatClient") ChatClient chatClient,
            ObjectMapper objectMapper,
            KkiruAnswerValidator validator,
            @Qualifier("communityTrendReasonExecutor") Executor executor,
            Clock clock,
            BusinessClockService businessClock)
            throws IOException {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.executor = executor;
        this.clock = clock;
        this.businessClock = businessClock;
        this.systemPrompt =
                new ClassPathResource("kkiru/trend-reason-prompt.txt")
                        .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 지금 캐시에 있는 이유(없으면 null)를 돌려주고, 새로 만들어야 하면 비동기로 만들기 시작한다. LLM을 기다리지 않는다. */
    public String reasonOrRequest(String ticker) {
        String key = key(ticker);
        Entry entry = cache.get(key);
        if (entry != null && entry.expiresAt().isAfter(clock.instant())) {
            return entry.reason();
        }
        request(ticker, key);
        return entry == null || !entry.expiresAt().isAfter(clock.instant()) ? null : entry.reason();
    }

    private void request(String ticker, String key) {
        if (!enabled || !inFlight.add(key)) {
            return; // 꺼져 있거나 이미 만드는 중
        }
        try {
            executor.execute(() -> generate(ticker, key));
        } catch (RejectedExecutionException e) {
            inFlight.remove(key);
            log.warn("[TREND_REASON_QUEUE_FULL] ticker={}", ticker);
        }
    }

    private void generate(String ticker, String key) {
        try {
            LocalDateTime from = businessClock.now().toLocalDate().atStartOfDay();
            List<String> items = recentTexts(ticker, from, from.plusDays(1));
            if (items.size() < MIN_ITEMS) {
                remember(key, null, TTL); // 요약할 만한 이야기가 아직 없다
                return;
            }
            String reason = ask(ticker, items);
            remember(key, reason, TTL);
        } catch (RuntimeException | IOException e) {
            log.warn("[TREND_REASON_FAILED] ticker={} {}", ticker, e.toString());
            remember(key, null, FAILURE_TTL);
        } finally {
            inFlight.remove(key);
        }
    }

    /** 오늘 그 종목의 보이는 글·댓글을 최신순으로 합쳐 최대 20개. @끼루·제어 문자를 지우고 150자로 자른다. */
    private List<String> recentTexts(String ticker, LocalDateTime from, LocalDateTime to) {
        record Item(LocalDateTime at, long id, String text) {}
        List<Item> items = new ArrayList<>();
        for (CommunityPostEntity p :
                postRepository.findRecentTickerPosts(
                        ticker, from, to, PageRequest.of(0, MAX_ITEMS))) {
            items.add(new Item(p.getCreatedAt(), p.getCommunityPostId(), p.getContent()));
        }
        for (CommunityCommentEntity c :
                commentRepository.findRecentTickerComments(
                        ticker, from, to, PageRequest.of(0, MAX_ITEMS))) {
            items.add(new Item(c.getCreatedAt(), c.getCommunityCommentId(), c.getContent()));
        }
        return items.stream()
                .sorted(Comparator.comparing(Item::at).thenComparingLong(Item::id).reversed())
                .map(i -> UserText.clean(i.text(), ITEM_LENGTH))
                .filter(t -> !t.isBlank())
                .limit(MAX_ITEMS)
                .toList();
    }

    /** LLM에게 한 문장을 받아 검증한다. 쓸 수 없으면 null. */
    private String ask(String ticker, List<String> items) throws IOException {
        String input = objectMapper.writeValueAsString(Map.of("ticker", ticker, "items", items));
        String raw =
                chatClient
                        .prompt()
                        .messages(new SystemMessage(systemPrompt), new UserMessage(input))
                        .call()
                        .content();
        int start = raw == null ? -1 : raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        String reason =
                objectMapper.readTree(raw.substring(start)).path("reason").asText("").trim();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > REASON_MAX_LENGTH) {
            return null;
        }
        // 8b 검증기를 재사용한다: 입력 글 전체를 근거 하나로 보고 한 문장이 그 안에서만 말하는지 본다.
        String evidenceId = "trend:" + ticker;
        KkiruAnswer answer =
                new KkiruAnswer(
                        List.of(new KkiruSentence(reason, List.of(evidenceId))),
                        Map.of(
                                evidenceId,
                                new Evidence(evidenceId, String.join(" ", items), null, true)));
        boolean valid = validator.validate(answer).isPresent();
        if (!valid) {
            log.info("[TREND_REASON_REJECTED] ticker={} reason={}", ticker, reason);
        }
        return valid ? reason : null;
    }

    private void remember(String key, String reason, Duration ttl) {
        cache.put(key, new Entry(reason, clock.instant().plus(ttl)));
    }

    /** 업무일·종목별 캐시 키. 날짜가 바뀌면 어제 이유는 쓰지 않는다. */
    private String key(String ticker) {
        return businessClock.now().toLocalDate() + ":" + ticker;
    }
}
