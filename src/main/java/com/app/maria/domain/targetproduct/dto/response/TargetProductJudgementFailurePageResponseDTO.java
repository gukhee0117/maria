package com.app.maria.domain.targetproduct.dto.response;

import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailurePageDTO;
import java.util.List;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Builder
public class TargetProductJudgementFailurePageResponseDTO {
    private List<TargetProductJudgementFailureListResponseDTO> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public TargetProductJudgementFailurePageResponseDTO(TargetProductJudgementFailurePageDTO dto) {
        this.content =
                dto.getContent().stream()
                        .map(TargetProductJudgementFailureListResponseDTO::new)
                        .toList();
        this.page = dto.getPage();
        this.size = dto.getSize();
        this.totalElements = dto.getTotalElements();
        this.totalPages = dto.getTotalPages();
    }
}
