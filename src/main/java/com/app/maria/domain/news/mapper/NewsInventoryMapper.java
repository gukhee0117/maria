package com.app.maria.domain.news.mapper;

import com.app.maria.domain.news.dto.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.ResultHandler;

@Mapper
public interface NewsInventoryMapper {
    void scanHoldings(ResultHandler<NewsHoldingRow> handler);

    List<NewsThemeLink> selectThemeLinks();
}
