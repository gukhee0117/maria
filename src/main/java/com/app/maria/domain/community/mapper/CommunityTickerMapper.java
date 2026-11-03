package com.app.maria.domain.community.mapper;

import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 국내·해외 종목 마스터에서 종목코드와 이름만 읽는다. */
@Mapper
public interface CommunityTickerMapper {
    /** 국내 종목 먼저, 그다음 해외 종목. 같은 시장 안에서는 종목코드 순. */
    List<TickerResponseDTO> selectAllTickers();

    boolean existsTicker(@Param("ticker") String ticker);

    /** tickers가 비어 있으면 호출하지 않는다(IN 절이 비면 SQL이 틀린다). */
    List<TickerResponseDTO> selectTickersByCodes(@Param("tickers") Collection<String> tickers);
}
