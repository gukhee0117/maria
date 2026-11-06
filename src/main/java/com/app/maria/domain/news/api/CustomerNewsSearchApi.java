package com.app.maria.domain.news.api;

import com.app.maria.domain.news.client.NaverNewsSearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/news/search")
@RequiredArgsConstructor
@Validated
public class CustomerNewsSearchApi {
    private final NaverNewsSearchClient client;

    @GetMapping
    public ResponseEntity<JsonNode> search(@RequestParam @NotBlank @Size(max = 100) String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.search(query));
    }
}
