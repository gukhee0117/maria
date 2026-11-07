package com.app.maria.domain.news.api;

import com.app.maria.domain.news.client.NaverNewsSearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
@RequestMapping("/api/customer/news/search")
@RequiredArgsConstructor
@Validated
public class CustomerNewsSearchApi {
    public record NaverNewsItem(String title, String link, String description, String pubDate) {}

    public record NaverNewsResponse(List<NaverNewsItem> items) {}

    private final NaverNewsSearchClient client;

    @GetMapping
    public ResponseEntity<NaverNewsResponse> search(
            @RequestParam @NotBlank @Size(max = 100) String query) {
        JsonNode response = client.search(query);
        var items =
                StreamSupport.stream(response.path("items").spliterator(), false)
                        .map(
                                item -> {
                                    String link = safeLink(item.path("link").asText(""));
                                    return new NaverNewsItem(
                                            plainText(item.path("title").asText("")),
                                            link == null ? "" : link,
                                            plainText(item.path("description").asText("")),
                                            item.path("pubDate").asText(""));
                                })
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new NaverNewsResponse(items));
    }

    private String plainText(String value) {
        return HtmlUtils.htmlUnescape(value).replaceAll("(?s)<[^>]*>", "").strip();
    }

    private String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                            && uri.getUserInfo() == null
                            && ("http".equalsIgnoreCase(uri.getScheme())
                                    || "https".equalsIgnoreCase(uri.getScheme()))
                    ? uri.toASCIIString()
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
