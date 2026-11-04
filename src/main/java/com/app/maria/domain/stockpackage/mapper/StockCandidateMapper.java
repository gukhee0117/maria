package com.app.maria.domain.stockpackage.mapper;

import com.app.maria.domain.stockpackage.vo.StockCandidateProductVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockCandidateMapper {
    /**
     * 상품군·테마·검색어 조건에 맞는 종목. types는 STOCK/FUND 이름이고 비어 있으면 전체다. themes는 하나라도 속하면 포함한다. keyword는 이름이나
     * 종목코드에 들어 있으면 포함하며 LIKE 특수문자는 호출하는 쪽에서 이스케이프한다.
     */
    List<StockCandidateProductVO> selectCandidateProducts(
            @Param("types") List<String> types,
            @Param("themes") List<String> themes,
            @Param("keyword") String keyword);

    /** 고를 수 있는 테마 이름 전체. */
    List<String> selectThemeNames();
}
