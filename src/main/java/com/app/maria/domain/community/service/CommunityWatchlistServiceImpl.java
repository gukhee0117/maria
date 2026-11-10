package com.app.maria.domain.community.service;

import com.app.maria.domain.community.mapper.CommunityWatchlistMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommunityWatchlistServiceImpl implements CommunityWatchlistService {
    private static final int MAX_ITEMS = 100;
    private final CommunityWatchlistMapper mapper;
    private final CommunityTickerService tickers;
    private final BusinessClockService clock;

    @Override
    @Transactional(readOnly = true)
    public List<String> list(Long customerId) {
        return mapper.findTickers(customerId);
    }

    @Override
    @Transactional
    public void add(Long customerId, String ticker) {
        validate(ticker);
        lockCustomer(customerId);
        tickers.requireExists(ticker);
        var current = mapper.findTickers(customerId);
        if (!current.contains(ticker) && current.size() >= MAX_ITEMS)
            throw new AppException(ErrorType.COMMUNITY_WATCHLIST_LIMIT_EXCEEDED);
        mapper.add(customerId, ticker, clock.now());
    }

    @Override
    @Transactional
    public void remove(Long customerId, String ticker) {
        validate(ticker);
        lockCustomer(customerId);
        // Deleted/delisted products must still be removable from an existing watchlist.
        mapper.remove(customerId, ticker);
    }

    private void lockCustomer(Long customerId) {
        // Lock before any snapshot reads so concurrent requests share one serialization point.
        if (mapper.lockCustomer(customerId) == null)
            throw new AppException(ErrorType.CUSTOMER_NOT_FOUND);
    }

    private void validate(String ticker) {
        if (ticker == null || !ticker.matches("[A-Z0-9][A-Z0-9.-]{0,19}"))
            throw new AppException(ErrorType.COMMUNITY_TICKER_NOT_FOUND);
    }
}
