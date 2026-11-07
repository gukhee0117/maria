package com.app.maria.domain.notification.mapper;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerNotificationSettingsMapper {
    List<CustomerNotificationConsentDTO> selectLatestByCustomerId(
            @Param("customerId") Long customerId);

    int insertConsent(
            @Param("customerId") Long customerId,
            @Param("consentType") String consentType,
            @Param("agreed") boolean agreed);
}
