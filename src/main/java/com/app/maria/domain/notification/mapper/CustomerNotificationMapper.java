package com.app.maria.domain.notification.mapper;

import com.app.maria.domain.notification.dto.CustomerNotificationVO;
import com.app.maria.domain.notification.dto.NotificationDeliveryDTO;
import com.app.maria.domain.notification.dto.SettlementEmailDetails;
import com.app.maria.domain.notification.type.NotificationConsentType;
import com.app.maria.domain.notification.type.NotificationType;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerNotificationMapper {
    int markVisibleAsRead(
            @Param("customerId") Long customerId,
            @Param("notificationIds") List<Long> ids,
            @Param("readAt") LocalDateTime readAt);

    SettlementEmailDetails selectSettlementEmailDetails(
            @Param("accountId") Long accountId, @Param("batchId") Long batchId);

    NotificationDeliveryDTO selectDeliveryTarget(
            @Param("customerId") Long customerId,
            @Param("consentType") NotificationConsentType consentType);

    int insertEvent(
            @Param("customerId") Long customerId,
            @Param("type") NotificationType type,
            @Param("relatedType") String relatedType,
            @Param("relatedId") Long relatedId,
            @Param("title") String title,
            @Param("content") String content,
            @Param("createdAt") LocalDateTime createdAt);

    List<CustomerNotificationVO> selectRecentByCustomerId(
            @Param("customerId") Long customerId, @Param("limit") int limit);

    int markAllUnreadAsRead(
            @Param("customerId") Long customerId, @Param("readAt") LocalDateTime readAt);

    boolean existsSettlementFinalized(
            @Param("customerId") Long customerId, @Param("batchId") Long batchId);
}
