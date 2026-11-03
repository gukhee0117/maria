package com.app.maria.domain.community.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 종목 드롭박스 한 줄. market은 DOMESTIC 또는 FOREIGN. MyBatis가 채우는 결과이기도 하다. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TickerResponseDTO {
    private String ticker;
    private String name;
    private String market;
}
