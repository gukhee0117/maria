package com.app.maria.domain.tax.mapper;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.HeldLotDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.type.TaxBasisType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaxMapper {
    List<SellLotDTO> selectFinalizedLotsByAccountIdsAndYear(
            @Param("accountIds") List<Long> accountIds,
            @Param("year") int year,
            @Param("calcBaseTime") LocalDateTime calcBaseTime);

    List<TaxRuleDTO> selectTaxRules();

    List<ExternalBuyDTO> selectExternalBuysByAccountIdsAndYear(
            @Param("accountIds") List<Long> accountIds,
            @Param("year") int year,
            @Param("calcBaseTime") LocalDateTime calcBaseTime);

    void insertCalculation(TaxCalculationDTO taxCalculationDTO);

    boolean existsByAccountAndBasis(
            @Param("accountId") Long accountId, @Param("basisType") TaxBasisType basisType);

    Optional<TaxCalculationDTO> selectLatestCalculation(@Param("accountId") Long accountId);

    List<HeldLotDTO> selectHeldLotsByAccountId(@Param("accountId") Long accountId);
}
