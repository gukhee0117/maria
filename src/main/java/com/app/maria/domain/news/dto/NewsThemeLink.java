package com.app.maria.domain.news.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsThemeLink {
    private String ticker;
    private String name;
    private String sectorName;
    private String theme;
}
