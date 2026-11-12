package com.app.maria.domain.news.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsThemePriority {
    private String theme;
    private Long priority;
}
