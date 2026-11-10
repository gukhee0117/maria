package com.app.maria.domain.community.service;

import java.util.List;

public interface CommunityWatchlistService {
    List<String> list(Long customerId);

    void add(Long customerId, String ticker);

    void remove(Long customerId, String ticker);
}
