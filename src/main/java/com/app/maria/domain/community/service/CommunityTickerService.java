package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.TickerRankResponseDTO;
import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import java.util.List;

public interface CommunityTickerService {
    List<TickerResponseDTO> list();

    /** 종목 마스터에 없는 종목코드면 COMMUNITY_TICKER_NOT_FOUND. */
    void requireExists(String ticker);

    /** 업무일 하루 동안 글이 많이 달린 종목. 시세가 없으니 등락률은 싣지 않는다. */
    List<TickerRankResponseDTO> ranking(int size);
}
