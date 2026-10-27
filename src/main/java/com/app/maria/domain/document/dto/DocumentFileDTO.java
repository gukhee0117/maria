package com.app.maria.domain.document.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DocumentFileDTO {
    private String fileName;
    private byte[] content;
}
