package com.app.maria.domain.notification.mapper;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import com.app.maria.domain.notification.type.NotificationConsentType;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerNotificationSettingsMapper {
    String selectLocalLoginId(@Param("customerId") Long customerId);

    String selectEmail(@Param("customerId") Long customerId);

    boolean hasRegisteredEmail(@Param("customerId") Long customerId);

    Long lockCustomer(@Param("customerId") Long customerId);

    List<CustomerNotificationConsentDTO> selectLatestByCustomerId(
            @Param("customerId") Long customerId);

    int insertConsent(
            @Param("customerId") Long customerId,
            @Param("consentType") NotificationConsentType consentType,
            @Param("agreed") boolean agreed,
            @Param("changedAt") LocalDateTime changedAt);
}
