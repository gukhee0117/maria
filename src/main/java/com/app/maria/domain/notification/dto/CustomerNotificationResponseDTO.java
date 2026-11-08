package com.app.maria.domain.notification.dto;

import com.app.maria.domain.notification.type.NotificationType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerNotificationResponseDTO {
    private Long notificationId;
    private NotificationType notificationType;
    private String title;
    private String content;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public CustomerNotificationResponseDTO(CustomerNotificationVO notification) {
        this.notificationId = notification.getNotificationId();
        this.notificationType = notification.getNotificationType();
        this.title = notification.getTitle();
        this.content = notification.getContent();
        this.readAt = notification.getReadAt();
        this.createdAt = notification.getCreatedAt();
    }
}
