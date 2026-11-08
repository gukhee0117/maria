package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.fundflow.dto.FundFlowFilterDTO;
import com.app.maria.domain.fundflow.dto.FundFlowFunnelStageDTO;
import com.app.maria.domain.fundflow.dto.request.FundFlowPeriodRequestDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowFunnelResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.FundFlowPeriod;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFundFlowServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 0, 0);

    @Mock FundFlowMapper fundFlowMapper;

    @Mock BusinessClockService businessClockService;

    @InjectMocks AdminFundFlowServiceImpl adminFundFlowService;

    @Test
    @DisplayName("업무시계 날짜는 집계 상한(clockDate)으로, 그 전날은 asOfDate로 쓴다")
    void getFunnelUsesClockDateAsUpperBoundAndPreviousDayAsAsOfDate() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectFunnel(any())).thenReturn(List.of());

        FundFlowFunnelResponseDTO result =
                adminFundFlowService.getFunnel(new FundFlowPeriodRequestDTO());

        ArgumentCaptor<FundFlowFilterDTO> captor = ArgumentCaptor.forClass(FundFlowFilterDTO.class);
        verify(fundFlowMapper).selectFunnel(captor.capture());
        assertThat(captor.getValue().getClockDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(captor.getValue().getAsOfDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.getAsOfDate()).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    @DisplayName("매퍼 결과의 단계와 분모 문구를 응답에 담는다")
    void getFunnelMapsStagesAndBasis() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectFunnel(any()))
                .thenReturn(
                        List.of(
                                FundFlowFunnelStageDTO.builder()
                                        .stageNo(1)
                                        .stage("웹 가입")
                                        .customers(95050L)
                                        .convTotal(new BigDecimal("1.0000"))
                                        .build(),
                                FundFlowFunnelStageDTO.builder()
                                        .stageNo(2)
                                        .stage("마이데이터 동의")
                                        .customers(85996L)
                                        .convPrev(new BigDecimal("0.9047"))
                                        .convTotal(new BigDecimal("0.9047"))
                                        .build()));

        FundFlowFunnelResponseDTO result =
                adminFundFlowService.getFunnel(new FundFlowPeriodRequestDTO());

        assertThat(result.getStages()).hasSize(2);
        assertThat(result.getStages().get(0).getConvPrev()).isNull();
        assertThat(result.getStages().get(1).getCustomers()).isEqualTo(85996L);
        assertThat(result.getStages().get(1).getConvPrev()).isEqualByComparingTo("0.9047");
        assertThat(result.getBasis()).isNotBlank();
    }

    @Test
    @DisplayName("기간을 고르면 기준일이 속한 달·분기의 첫날이 하한(periodFrom)이 된다")
    void getFunnelSetsPeriodFromByPeriod() {
        when(businessClockService.now()).thenReturn(NOW);
        when(fundFlowMapper.selectFunnel(any())).thenReturn(List.of());

        FundFlowPeriodRequestDTO month = new FundFlowPeriodRequestDTO();
        month.setPeriod(FundFlowPeriod.MONTH);
        FundFlowPeriodRequestDTO quarter = new FundFlowPeriodRequestDTO();
        quarter.setPeriod(FundFlowPeriod.QUARTER);
        adminFundFlowService.getFunnel(new FundFlowPeriodRequestDTO());
        adminFundFlowService.getFunnel(month);
        adminFundFlowService.getFunnel(quarter);

        ArgumentCaptor<FundFlowFilterDTO> captor = ArgumentCaptor.forClass(FundFlowFilterDTO.class);
        verify(fundFlowMapper, times(3)).selectFunnel(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(FundFlowFilterDTO::getPeriodFrom)
                .containsExactly(null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1));
    }
}
