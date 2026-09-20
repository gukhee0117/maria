package com.app.maria.domain.foreignproduct.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.foreignproduct.dto.response.ForeignProductResponseDTO;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.foreignproduct.service.ForeignProductService;
import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import com.app.maria.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ForeignProductApiTest {

    private ForeignProductService foreignProductService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        foreignProductService = mock(ForeignProductService.class);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new ForeignProductApi(foreignProductService))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @Test
    void getAllForeignProductsReturnsProductList() throws Exception {
        ForeignProductResponseDTO response =
                ForeignProductResponseDTO.builder()
                        .foreignProductId(1L)
                        .ticker("AAPL")
                        .name("애플")
                        .market("NASDAQ")
                        .currency("USD")
                        .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                        .build();
        when(foreignProductService.getAllForeignProducts()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/foreign-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("종목 전체 조회 성공"))
                .andExpect(jsonPath("$.data[0].ticker").value("AAPL"));
    }

    @Test
    void getAllForeignProductsReturnsEmptyListWhenNoProductsExist() throws Exception {
        when(foreignProductService.getAllForeignProducts()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/foreign-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getForeignProductReturnsProductWhenProductExists() throws Exception {
        ForeignProductResponseDTO response =
                ForeignProductResponseDTO.builder()
                        .foreignProductId(1L)
                        .ticker("AAPL")
                        .name("애플")
                        .market("NASDAQ")
                        .currency("USD")
                        .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                        .build();
        when(foreignProductService.getForeignProduct(1L)).thenReturn(response);

        mockMvc.perform(get("/api/admin/foreign-products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("종목 단건 조회 성공"))
                .andExpect(jsonPath("$.data.ticker").value("AAPL"));
    }

    @Test
    void getForeignProductReturnsNotFoundWhenProductDoesNotExist() throws Exception {
        when(foreignProductService.getForeignProduct(999L))
                .thenThrow(
                        new ForeignProductNotFoundException("존재하지 않는 종목입니다. foreignProductId=999"));

        mockMvc.perform(get("/api/admin/foreign-products/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 종목입니다. foreignProductId=999"));
    }
}
