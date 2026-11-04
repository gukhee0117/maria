package com.app.maria.domain.stockpackage.mapper;

import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
import com.app.maria.domain.stockpackage.vo.StockProductThemeVO;
import com.app.maria.domain.stockpackage.vo.StockProductVO;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockPackageMapper {
    /** 생성된 stockPackageId가 pkg에 채워진다. */
    int insertPackage(StockPackageVO pkg);

    int insertItems(
            @Param("stockPackageId") Long stockPackageId,
            @Param("items") List<StockPackageItemVO> items);

    List<StockPackageVO> selectSummariesByCustomerId(@Param("customerId") Long customerId);

    Optional<StockPackageVO> selectByIdAndCustomerId(
            @Param("stockPackageId") Long stockPackageId, @Param("customerId") Long customerId);

    /** 수정·삭제가 겹치지 않도록 패키지 행을 잠그고 읽는다. */
    Optional<StockPackageVO> selectByIdAndCustomerIdForUpdate(
            @Param("stockPackageId") Long stockPackageId, @Param("customerId") Long customerId);

    List<StockPackageItemVO> selectItemsByPackageId(@Param("stockPackageId") Long stockPackageId);

    int updatePackage(StockPackageVO pkg);

    int deleteItemsByPackageId(@Param("stockPackageId") Long stockPackageId);

    int deletePackage(
            @Param("stockPackageId") Long stockPackageId, @Param("customerId") Long customerId);

    List<StockProductVO> selectProductsByIds(@Param("ids") List<Long> ids);

    /** 종목들이 속한 테마. 테마가 없는 종목은 결과에 나오지 않는다. */
    List<StockProductThemeVO> selectThemesByProductIds(@Param("ids") List<Long> ids);

    /** 이 패키지로 낸 주문 수. 주문이 있으면 패키지를 지울 수 없다. */
    int countOrdersByPackageId(@Param("stockPackageId") Long stockPackageId);
}
