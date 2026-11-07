package com.app.maria.domain.news.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewsHoldingRow {
    private Long customerId;
    private String ticker;
    private String name;
}
