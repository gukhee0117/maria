package com.app.maria.domain.document.api;

import com.app.maria.domain.document.dto.DocumentFileDTO;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class DocumentResponses {

    private DocumentResponses() {}

    /** 브라우저가 화면에 열지 않고 저장하도록 attachment로 내려 보낸다. */
    static ResponseEntity<byte[]> pdf(DocumentFileDTO file) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .cacheControl(CacheControl.noStore())
                .contentLength(file.getContent().length)
                .body(file.getContent());
    }
}
