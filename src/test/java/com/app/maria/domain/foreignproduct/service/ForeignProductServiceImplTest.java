package com.app.maria.domain.foreignproduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.dto.response.ForeignProductResponseDTO;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.domain.foreignproduct.type.ForeignProductType;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForeignProductServiceImplTest {

    @Mock private ForeignProductMapper foreignProductMapper;

    @InjectMocks private ForeignProductServiceImpl foreignProductService;

    @Test
    void getAllForeignProductsReturnsAllProductsWhenProductsExist() {
        ForeignProductDTO dto1 =
                ForeignProductDTO.builder()
                        .foreignProductId(1L)
                        .ticker("AAPL")
                        .name("애플")
                        .market("NASDAQ")
                        .currency("USD")
                        .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                        .build();
        ForeignProductDTO dto2 =
                ForeignProductDTO.builder()
                        .foreignProductId(2L)
                        .ticker("TSLA")
                        .name("테슬라")
                        .market("NASDAQ")
                        .currency("USD")
                        .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                        .build();
        when(foreignProductMapper.selectAll()).thenReturn(List.of(dto1, dto2));

        List<ForeignProductResponseDTO> result = foreignProductService.getAllForeignProducts();

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(ForeignProductResponseDTO::getTicker)
                .containsExactly("AAPL", "TSLA");
    }

    @Test
    void getAllForeignProductsReturnsEmptyListWhenNoProductsExist() {
        when(foreignProductMapper.selectAll()).thenReturn(List.of());

        List<ForeignProductResponseDTO> result = foreignProductService.getAllForeignProducts();

        assertThat(result).isEmpty();
    }

    @Test
    void getForeignProductReturnsProductWhenProductExists() {
        ForeignProductDTO dto =
                ForeignProductDTO.builder()
                        .foreignProductId(1L)
                        .ticker("AAPL")
                        .name("애플")
                        .market("NASDAQ")
                        .currency("USD")
                        .foreignProductType(ForeignProductType.FOREIGN_STOCK)
                        .build();
        when(foreignProductMapper.selectById(1L)).thenReturn(Optional.of(dto));

        ForeignProductResponseDTO result = foreignProductService.getForeignProduct(1L);

        assertThat(result.getTicker()).isEqualTo("AAPL");
        assertThat(result.getName()).isEqualTo("애플");
    }

    @Test
    void getForeignProductThrowsNotFoundExceptionWhenProductDoesNotExist() {
        when(foreignProductMapper.selectById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> foreignProductService.getForeignProduct(999L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorType())
                .isEqualTo(ErrorType.FOREIGN_PRODUCT_NOT_FOUND);
    }
}
