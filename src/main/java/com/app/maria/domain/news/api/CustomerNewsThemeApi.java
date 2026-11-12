package com.app.maria.domain.news.api;

import com.app.maria.domain.news.service.*;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/news/themes")
public class CustomerNewsThemeApi {
    public record StockThemes(String ticker, String name, String sectorName, List<String> themes) {}

    private final NewsCustomerContextService contexts;
    private final NewsThemeService themes;

    @GetMapping
    public ApiResponseDTO<List<StockThemes>> mine(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var c = contexts.loadHoldings(principal.customerId());
        var mapping = themes.themes(c.tickers());
        return ApiResponseDTO.of(
                "보유 종목 테마 조회 성공",
                c.tickers().stream()
                        .sorted()
                        .map(
                                t ->
                                        new StockThemes(
                                                t,
                                                c.stockNames().getOrDefault(t, t),
                                                c.stockSectors().get(t),
                                                List.copyOf(mapping.getOrDefault(t, Set.of()))))
                        .toList());
    }
}
