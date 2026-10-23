package com.app.maria.domain.foreignproduct.service;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.foreignproduct.dto.response.ForeignProductResponseDTO;
import com.app.maria.domain.foreignproduct.mapper.ForeignProductMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class ForeignProductServiceImpl implements ForeignProductService {

    private final ForeignProductMapper foreignProductMapper;

    @Override
    public List<ForeignProductResponseDTO> getAllForeignProducts() {
        List<ForeignProductDTO> products = foreignProductMapper.selectAll();

        return products.stream().map(ForeignProductResponseDTO::new).toList();
    }

    @Override
    public ForeignProductResponseDTO getForeignProduct(Long foreignProductId) {
        return foreignProductMapper
                .selectById(foreignProductId)
                .map(ForeignProductResponseDTO::new)
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorType.FOREIGN_PRODUCT_NOT_FOUND, foreignProductId));
    }
}
