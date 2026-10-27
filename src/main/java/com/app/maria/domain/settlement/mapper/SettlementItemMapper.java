package com.app.maria.domain.settlement.mapper;

import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SettlementItemMapper {

    int insertItemsForTargets(SettlementBatchDTO batch);

    List<SettlementItemDTO> selectPendingItems(SettlementItemDTO cursor);

    int updateItemResult(SettlementItemDTO item);

    int markPendingItemsFailed(SettlementItemDTO item);

    int insertRetryItem(SettlementItemDTO item);

    int insertRetryItemsForFailedBatch(Long batchId);

    Optional<SettlementItemDTO> selectItemById(Long itemId);

    Optional<SettlementItemDTO> selectItemByIdForUpdate(Long itemId);

    List<SettlementItemDTO> selectItemsByExchangeIdForUpdate(Long exchangeId);

    boolean existsSuccessfulItemByExchangeId(Long exchangeId);

    boolean existsPendingItemByExchangeId(Long exchangeId);

    int countPendingItems(Long batchId);

    int countFailedItems(Long batchId);

    boolean isAccountSettlementCompleted(
            @Param("batchId") Long batchId, @Param("accountId") Long accountId);
}
