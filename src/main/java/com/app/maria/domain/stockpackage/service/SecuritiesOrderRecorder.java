package com.app.maria.domain.stockpackage.service;

import com.app.maria.domain.stockpackage.mapper.StockOrderMapper;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import com.app.maria.global.client.securities.SecuritiesTradeClient;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객 주문의 체결을 증권사에 보내고, 보냈다는 표시({@code securities_recorded_at})를 남긴다. 표시가 비어 있는 체결 주문이 곧 보낼 일
 * 목록(아웃박스)이라 주문과 같은 트랜잭션으로 저장되고, 커밋 직후 한 번 보내 보고 못 보낸 것은 {@link #retryUnrecorded()}가 이어서 보낸다. 같은
 * maria 주문번호는 증권사가 중복으로 기록하지 않아 두 번 보내도 안전하다.
 */
@Slf4j
@Component
public class SecuritiesOrderRecorder {
    private static final int RETRY_BATCH = 50;

    private final SecuritiesTradeClient tradeClient;
    private final StockOrderMapper stockOrderMapper;
    private final TransactionTemplate markTx;

    @Value("${maria.stock-order.securities-retry-enabled:true}")
    private boolean retryEnabled = true;

    public SecuritiesOrderRecorder(
            SecuritiesTradeClient tradeClient,
            StockOrderMapper stockOrderMapper,
            PlatformTransactionManager transactionManager) {
        this.tradeClient = tradeClient;
        this.stockOrderMapper = stockOrderMapper;
        // 커밋 직후 훅에서도 표시가 실제로 커밋되도록 항상 새 트랜잭션에서 남긴다
        this.markTx = new TransactionTemplate(transactionManager);
        this.markTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 한 건을 보낸다. 실패해도 던지지 않고 false를 돌려준다(표시가 비어 있어 재시도 대상으로 남는다). */
    public boolean record(String ciHash, StockOrderVO order) {
        try {
            tradeClient.record(
                    ciHash,
                    order.getSide(),
                    order.getTicker(),
                    order.getQty(),
                    order.getPrice(),
                    order.getProcessedAt(),
                    order.getDomesticOrderId());
            markTx.executeWithoutResult(
                    status -> stockOrderMapper.markSecuritiesRecorded(order.getDomesticOrderId()));
            return true;
        } catch (RuntimeException e) {
            // 호스트·응답 본문이 섞일 수 있어 예외 메시지는 로그에 싣지 않는다
            log.warn("증권사 거래 기록 보류 orderId={} (재시도 대상)", order.getDomesticOrderId());
            return false;
        }
    }

    // ponytail: 영구 거절(4xx) 건도 계속 다시 보내고, 앞쪽 50건이 전부 영구 실패면 뒤가 밀린다. 시도 횟수·FAILED 상태는 필요해지면 추가
    @Scheduled(fixedDelayString = "${maria.stock-order.securities-retry-delay-millis:5000}")
    public void retryUnrecorded() {
        if (!retryEnabled) return;
        List<StockOrderVO> pending = stockOrderMapper.selectUnrecordedCustomerOrders(RETRY_BATCH);
        pending.forEach(order -> record(order.getCiHash(), order));
    }
}
