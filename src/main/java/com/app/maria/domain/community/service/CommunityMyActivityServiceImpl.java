package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.MySummaryResponseDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내가 쓴 글·댓글(최신순)과 프로필 카드 숫자. ticker를 주면 그 종목만, 비우면 전 종목이다. */
@Service
@RequiredArgsConstructor
public class CommunityMyActivityServiceImpl implements CommunityMyActivityService {
    private static final long NO_CURSOR = Long.MAX_VALUE;

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CustomerRepository customerRepository;
    private final CommunityPostAssembler postAssembler;
    private final CommunityCommentAssembler commentAssembler;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public PostPageResponseDTO myPosts(Long customerId, String ticker, Long cursor, int size) {
        boolean allTickers = ticker == null || ticker.isBlank();
        List<CommunityPostEntity> rows =
                postRepository.findMine(
                        customerId,
                        cursor == null ? NO_CURSOR : cursor,
                        allTickers,
                        allTickers ? "" : CommunityPostServiceImpl.requireValidTicker(ticker),
                        PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<CommunityPostEntity> page = hasMore ? rows.subList(0, size) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).getCommunityPostId() : null;
        return new PostPageResponseDTO(postAssembler.assemble(page, customerId), nextCursor);
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public CommentPageResponseDTO myComments(
            Long customerId, String ticker, Long cursor, int size) {
        boolean allTickers = ticker == null || ticker.isBlank();
        List<CommunityCommentEntity> rows =
                commentRepository.findMine(
                        customerId,
                        cursor == null ? NO_CURSOR : cursor,
                        allTickers,
                        allTickers ? "" : CommunityPostServiceImpl.requireValidTicker(ticker),
                        PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<CommunityCommentEntity> page = hasMore ? rows.subList(0, size) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).getCommunityCommentId() : null;
        return new CommentPageResponseDTO(commentAssembler.assemble(page, customerId), nextCursor);
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public MySummaryResponseDTO summary(Long customerId) {
        CustomerEntity customer =
                customerRepository
                        .findById(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        return new MySummaryResponseDTO(
                customer.getNickname(),
                postRepository.countByCustomerIdAndStatusNot(customerId, CommunityStatus.DELETED),
                commentRepository.countByCustomerIdAndStatusNot(
                        customerId, CommunityStatus.DELETED),
                customer.isInvestorTypePublic());
    }
}
