package com.app.maria.domain.stockpackage.mapper;

import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockOrderMapper {
    /** 생성된 domesticOrderId가 order에 채워진다. */
    int insertOrder(StockOrderVO order);

    /** 같은 주문 키로 이미 처리한 주문. 있으면 다시 처리하지 않고 그 결과를 돌려준다. */
    Optional<StockOrderVO> selectByIdempotencyKey(
            @Param("accountId") Long accountId, @Param("idempotencyKey") String idempotencyKey);

    /** 가정산(PROVISIONAL) 환전 금액 합. 예수금에 들어 있지만 아직 확정되지 않아 주문에 쓸 수 없다. */
    BigDecimal sumProvisionalAmount(@Param("accountId") Long accountId);

    /** 계좌 행을 이미 잠근 상태에서만 부른다(같은 계좌의 주문은 계좌 락으로 줄을 세운다). */
    Optional<StockBalanceVO> selectBalanceForUpdate(
            @Param("accountId") Long accountId, @Param("domesticProductId") Long domesticProductId);

    /** 누군가 잔량을 들고 있는 상품 id. 생성기 매수 후보의 재료다. */
    List<Long> selectHeldProductIds();

    /** 이 계좌에서 잔량이 있는 보유. 생성기 매도 후보다. */
    List<StockBalanceVO> selectHeldBalancesByAccount(@Param("accountId") Long accountId);

    int insertBalance(StockBalanceVO balance);

    int updateBalance(StockBalanceVO balance);

    /** 고객의 패키지 주문 내역(체결·거부), 최신순. stockPackageId가 null이면 모든 패키지. 패키지 이름이 함께 붙는다. */
    List<StockOrderVO> selectOrdersByCustomer(
            @Param("customerId") Long customerId,
            @Param("stockPackageId") Long stockPackageId,
            @Param("limit") int limit);

    /** 이 패키지로 체결된 주문을 오래된 순으로. 패키지 보유 수량·평균단가를 계산하는 재료다. */
    List<StockOrderVO> selectExecutedByPackageAsc(@Param("stockPackageId") Long stockPackageId);

    /** 고객의 모든 패키지에서 체결된 주문을 오래된 순으로. 패키지별로 나눠 보유를 계산한다. */
    List<StockOrderVO> selectExecutedByCustomerAsc(@Param("customerId") Long customerId);

    /**
     * 체결된 주문 한 건의 예수금 변동(domestic_order.amount)을 계좌 예수금에 반영한다. 금액을 호출하는 쪽에서 받지 않고 이미 기록된 체결 주문 행에서만
     * 읽으므로, 임의 금액을 넣는 입금 경로가 되지 않는다. 체결(EXECUTED)이 아니거나 다른 계좌의 주문이면 아무것도 바뀌지 않는다.
     */
    int applyOrderAmount(
            @Param("accountId") Long accountId, @Param("domesticOrderId") Long domesticOrderId);

    /** 증권사에 기록을 마친 주문에 시각을 남긴다. 이미 남아 있으면 바꾸지 않는다. */
    int markSecuritiesRecorded(@Param("domesticOrderId") Long domesticOrderId);

    /** 아직 증권사에 못 보낸 고객 체결 주문을 오래된 순으로(아웃박스). ticker·ciHash가 붙는다. */
    List<StockOrderVO> selectUnrecordedCustomerOrders(@Param("limit") int limit);
}
