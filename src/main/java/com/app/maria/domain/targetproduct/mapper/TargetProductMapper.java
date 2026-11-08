package com.app.maria.domain.targetproduct.mapper;

import com.app.maria.domain.targetproduct.dto.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TargetProductMapper {

    void insertJudgement(TargetProductJudgementDTO judgementDTO);

    boolean existsByMydataTradeId(Long mydataTradeId);

    Optional<TargetProductJudgementFailureDTO> selectFailureByMydataTradeId(Long mydataTradeId);

    void upsertFailure(TargetProductJudgementFailureDTO failureDTO);

    List<TargetProductJudgementListDTO> selectJudgements(TargetProductSearchDTO searchDTO);

    int countJudgements();

    int countFilteredJudgements(TargetProductSearchDTO searchDTO);

    TargetProductSummaryDTO selectSummary(
            @Param("today") LocalDate today, @Param("tomorrow") LocalDate tomorrow);

    void deleteFailureByMydataTradeId(Long mydataTradeId);

    default List<TargetProductJudgementFailureListDTO> selectPermanentFailures(
            LocalDateTime cutoff, int offset, int size) {
        return selectPermanentFailuresOrdered(
                cutoff, "f.first_failed_at ASC, f.failure_id ASC", offset, size);
    }

    /** orderBy 는 OrderBySpec 이 만든 안전한 문자열만 넘긴다. */
    List<TargetProductJudgementFailureListDTO> selectPermanentFailuresOrdered(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("orderBy") String orderBy,
            @Param("offset") int offset,
            @Param("size") int size);

    int countPermanentFailures(@Param("cutoff") LocalDateTime cutoff);
}
