package com.app.maria.domain.news.mapper;

import com.app.maria.domain.news.dto.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

@Mapper
public interface NewsInventoryMapper {
    void scanHoldings(ResultHandler<NewsHoldingRow> handler);

    List<NewsThemeLink> selectThemeLinks();

    long selectLatestOrderId(@Param("customerId") Long customerId);

    List<NewsThemePriority> selectHoldingThemePriorities(@Param("customerId") Long customerId);
}
