package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.PostUpdateRequestDTO;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.entity.CommunityContent;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 커뮤니티 글 쓰기·수정·삭제·목록·상세·인기글. */
@Service
@RequiredArgsConstructor
public class CommunityPostServiceImpl implements CommunityPostService {
    static final int KEYWORD_MAX_LENGTH = 30;

    private static final long NO_CURSOR = Long.MAX_VALUE;
    private static final Pattern TICKER_FORMAT = Pattern.compile("^[A-Za-z0-9.\\-]{1,20}$");

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostAssembler assembler;
    private final CommunityTextGuard textGuard;
    private final CommunityTickerService tickerService;
    private final KkiruCallPlanner kkiruCallPlanner;
    private final BusinessClockService clock;
    private final ApplicationEventPublisher events;

    @Value("${maria.community.popular.days:7}")
    private int popularDays;

    /**
     * 글을 쓴다. 본문에 @끼루가 있으면 호출 상태를 함께 저장하고, PENDING이면 이벤트를 낸다. READ_COMMITTED는 끼루 한도
     * 확인용(KkiruCallPlanner 참고).
     */
    @Override
    @Transactional(
            transactionManager = "jpaTransactionManager",
            isolation = Isolation.READ_COMMITTED)
    public PostResponseDTO write(Long customerId, PostWriteRequestDTO request) {
        String content = CommunityContent.requireValid(request.content());
        String ticker = normalizeTicker(request.ticker());
        if (ticker != null) {
            tickerService.requireExists(ticker);
        }
        String sanitized = textGuard.requireAllowed(content);
        KkiruStatus kkiruStatus = kkiruCallPlanner.plan(content, customerId, null);

        CommunityPostEntity post =
                postRepository.save(
                        CommunityPostEntity.write(
                                customerId, ticker, sanitized, kkiruStatus, clock.now()));
        if (kkiruStatus == KkiruStatus.PENDING) {
            events.publishEvent(new KkiruCallRequestedEvent(post.getCommunityPostId(), null));
        }
        return assembler.assemble(List.of(post), customerId).get(0);
    }

    /** 본인 글 본문 수정. 끼루가 답했거나 답을 만드는 중인 글은 고칠 수 없다. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public PostResponseDTO edit(Long customerId, Long postId, PostUpdateRequestDTO request) {
        CommunityPostEntity post = lockOwnPost(customerId, postId);
        String content = CommunityContent.requireValid(request.content());
        post.edit(textGuard.requireAllowed(content), clock.now());
        return assembler.assemble(List.of(post), customerId).get(0);
    }

    /**
     * 내 글의 끼루 호출 다시 묻기. 행을 잠가 본인·상태·보임 여부를 다시 확인하고 PENDING으로 돌린 뒤 커밋 뒤 이벤트를 낸다. 새 행을 만들지 않아 호출 횟수
     * 집계는 그대로지만 재시도 기록이 남아 하루 한도·전체 상한에 반영되고, 행당 횟수 상한도 확인한다.
     */
    @Override
    @Transactional(
            transactionManager = "jpaTransactionManager",
            isolation = Isolation.READ_COMMITTED)
    public PostResponseDTO retryKkiru(Long customerId, Long postId) {
        CommunityPostEntity post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        if (!post.getCustomerId().equals(customerId)) {
            throw new AppException(ErrorType.COMMUNITY_NOT_AUTHOR, postId);
        }
        // FAILED 글은 고칠 수 있어서, 고친 본문이 멘션을 잃었거나 범위 밖이 됐으면 다시 묻기로 우회하지 못하게 한다.
        if (post.getKkiruStatus() != KkiruStatus.FAILED
                || !post.isVisible()
                || !kkiruCallPlanner.canRetry(post.getContent())) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED, postId);
        }
        kkiruCallPlanner.requireRetryLimits(customerId, post.getKkiruRetryCount());
        post.restartKkiru();
        events.publishEvent(new KkiruCallRequestedEvent(postId, null));
        return assembler.assemble(List.of(post), customerId).get(0);
    }

    /** 본인 글 삭제(소프트). */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public void delete(Long customerId, Long postId) {
        CommunityPostEntity post = lockOwnPost(customerId, postId);
        // 신고로 숨겨진 글은 관리자가 처리할 때까지 작성자가 지워 처리 대기 신고를 막다른 길로 만들 수 없다.
        if (post.getStatus() == CommunityStatus.HIDDEN) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_EDITABLE, postId);
        }
        post.markDeleted();
    }

    /** 종목 화면 목록. ticker가 비면 전체, GENERAL이면 종목 없는 글, 아니면 그 종목. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public PostPageResponseDTO list(
            Long viewerId, String ticker, String keyword, Long cursor, int size) {
        boolean allTickers = ticker == null || ticker.isBlank();
        boolean general = !allTickers && GENERAL.equalsIgnoreCase(ticker.strip());
        String tickerFilter = (allTickers || general) ? "" : requireValidTicker(ticker);
        String query = keyword == null ? "" : keyword.strip();
        if (query.codePointCount(0, query.length()) > KEYWORD_MAX_LENGTH) {
            throw new AppException(ErrorType.COMMUNITY_CONTENT_INVALID);
        }
        long from = cursor == null ? NO_CURSOR : cursor;

        List<CommunityPostEntity> rows =
                postRepository.findFeed(
                        from,
                        allTickers,
                        general,
                        tickerFilter,
                        !query.isEmpty(),
                        likePattern(query),
                        PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<CommunityPostEntity> page = hasMore ? rows.subList(0, size) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).getCommunityPostId() : null;
        return new PostPageResponseDTO(assembler.assemble(page, viewerId), nextCursor);
    }

    /** 인기글: 최근 며칠 안에 쓴 글 중 댓글이 많은 순. ticker가 비면 전체 종목. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public PostPageResponseDTO popular(Long viewerId, String ticker, int size) {
        boolean allTickers = ticker == null || ticker.isBlank();
        boolean general = !allTickers && GENERAL.equalsIgnoreCase(ticker.strip());
        String tickerFilter = (allTickers || general) ? "" : requireValidTicker(ticker);
        LocalDateTime since = clock.now().minusDays(popularDays);
        List<CommunityPostEntity> rows =
                postRepository.findPopular(
                        since, allTickers, general, tickerFilter, PageRequest.of(0, size));
        return new PostPageResponseDTO(assembler.assemble(rows, viewerId), null);
    }

    /** 글 한 건. 삭제된 글은 보이는 댓글이 있을 때만 안내 문구로 보이고, 없으면 찾을 수 없다. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public PostResponseDTO detail(Long viewerId, Long postId) {
        CommunityPostEntity post =
                postRepository
                        .findById(postId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        if (post.getStatus() == CommunityStatus.DELETED
                && commentRepository.countByCommunityPostIdAndStatus(
                                postId, CommunityStatus.VISIBLE)
                        == 0) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId);
        }
        return assembler.assemble(List.of(post), viewerId).get(0);
    }

    /** 수정·삭제 공통: 글을 잠그고 본인 글인지 확인한다. 삭제된 글은 없는 글로 본다. */
    private CommunityPostEntity lockOwnPost(Long customerId, Long postId) {
        CommunityPostEntity post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        if (post.getStatus() == CommunityStatus.DELETED) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId);
        }
        if (!post.getCustomerId().equals(customerId)) {
            throw new AppException(ErrorType.COMMUNITY_NOT_AUTHOR, postId);
        }
        return post;
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }
        return requireValidTicker(ticker.strip());
    }

    /** 영문·숫자·점·하이픈 1~20자만 받는다. 대문자로 맞춘다. */
    static String requireValidTicker(String ticker) {
        String stripped = ticker.strip();
        if (!TICKER_FORMAT.matcher(stripped).matches()) {
            throw new AppException(ErrorType.COMMUNITY_TICKER_NOT_FOUND, ticker);
        }
        return stripped.toUpperCase(Locale.ROOT);
    }

    /** LIKE 검색 패턴. 검색어의 %·_·! 는 글자 그대로 찾도록 !로 이스케이프한다(쿼리의 escape '!'와 짝). */
    static String likePattern(String keyword) {
        if (keyword.isEmpty()) {
            return "";
        }
        String escaped =
                keyword.toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
