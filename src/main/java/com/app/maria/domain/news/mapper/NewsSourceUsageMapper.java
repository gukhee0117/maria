package com.app.maria.domain.news.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Persists provider request reservations so monthly limits survive restarts and multiple instances.
 */
@Mapper
public interface NewsSourceUsageMapper {
    int ensureMonth(@Param("usageMonth") String usageMonth);

    int reserveRequest(@Param("usageMonth") String usageMonth, @Param("limit") int limit);
}
