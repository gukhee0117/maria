package com.app.maria.domain.community.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommunityWatchlistMapper {
    Long lockCustomer(@Param("customerId") Long customerId);

    List<String> findTickers(@Param("customerId") Long customerId);

    void add(
            @Param("customerId") Long customerId,
            @Param("ticker") String ticker,
            @Param("createdAt") LocalDateTime createdAt);

    void remove(@Param("customerId") Long customerId, @Param("ticker") String ticker);
}
