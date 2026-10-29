package com.app.maria.domain.risk.mapper;

import com.app.maria.domain.risk.dto.RiskSyncStateDTO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RiskSyncMapper {
    Integer acquireLock();

    Integer releaseLock();

    RiskSyncStateDTO findState();

    int saveState(RiskSyncStateDTO state);
}
