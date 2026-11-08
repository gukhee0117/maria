package com.app.maria.domain.notification.mapper;

import com.app.maria.domain.notification.dto.CustomerNotificationVO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerNotificationMapper {
    List<CustomerNotificationVO> selectRecentByCustomerId(
            @Param("customerId") Long customerId, @Param("limit") int limit);

    int insertSettlementFinalized(
            @Param("customerId") Long customerId,
            @Param("batchId") Long batchId,
            @Param("title") String title,
            @Param("content") String content,
            @Param("completedAt") LocalDateTime completedAt);

    int markAllUnreadAsRead(
            @Param("customerId") Long customerId, @Param("readAt") LocalDateTime readAt);

    boolean existsSettlementFinalized(
            @Param("customerId") Long customerId, @Param("batchId") Long batchId);
}
