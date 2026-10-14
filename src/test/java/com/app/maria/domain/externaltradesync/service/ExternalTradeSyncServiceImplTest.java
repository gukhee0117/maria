package com.app.maria.domain.externaltradesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.dto.CustomerCiHashDTO;
import com.app.maria.domain.customer.mapper.CustomerMapper;
import com.app.maria.domain.externaltradesync.dto.ExternalTradeSyncCursorDTO;
import com.app.maria.domain.externaltradesync.dto.request.MydataTradeRequestDTO;
import com.app.maria.domain.externaltradesync.dto.response.MydataTradeResponseDTO;
import com.app.maria.domain.externaltradesync.mapper.ExternalTradeSyncCursorMapper;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailureDTO;
import com.app.maria.domain.targetproduct.mapper.TargetProductMapper;
import com.app.maria.domain.targetproduct.service.TargetProductService;
import com.app.maria.global.client.mydatatrade.MydataTradeClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExternalTradeSyncProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalTradeSyncServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 31);

    @Mock private CustomerMapper customerMapper;

    @Mock private ExternalTradeSyncCursorMapper cursorMapper;

    @Mock private MydataTradeClient mydataTradeClient;

    @Mock private TargetProductMapper targetProductMapper;

    @Mock private TargetProductService targetProductService;

    @Mock private BusinessClockService businessClockService;

    @Mock private ExternalTradeSyncProperties externalTradeSyncProperties;

    @InjectMocks private ExternalTradeSyncServiceImpl externalTradeSyncService;

    @BeforeEach
    void setUpClock() {
        // 스텁 안 하면 Mockito가 int 기본값 0을 반환해 모든 기존 실패가 즉시 영구실패로
        // 판정돼버리므로, 운영값(application.yaml)과 맞춰 명시적으로 스텁한다.
        lenient().when(externalTradeSyncProperties.getMaxJudgeFailureDays()).thenReturn(3);
        // 대부분의 테스트가 syncCustomer()를 거쳐 now()를 호출하지만,
        // 고객이 아예 없는 테스트(syncAllDoesNothingWhenNoCustomers)는 호출하지 않으므로 lenient 처리
        lenient().when(businessClockService.now()).thenReturn(TODAY.atStartOfDay());
        // judge()가 신규 판정 시 judgementId를 꺼내 쓰므로, 실패를 검증하는 테스트가 개별
        // thenThrow로 덮어쓰지 않는 한 기본적으로 성공 DTO를 반환하도록 lenient 처리
        lenient()
                .when(targetProductService.judge(any()))
                .thenReturn(TargetProductJudgementDTO.builder().judgementId(1L).build());
        // judgeTrade()가 매 거래마다 먼저 실패 기록을 조회하므로, 실패 이력이 없는 거래를
        // 다루는 대다수 테스트가 매번 스텁하지 않도록 기본값을 비워둔다(Mockito 기본값은
        // Optional이 아니라 null이라 안 비워두면 existingFailure.map(...)에서 NPE가 난다).
        lenient()
                .when(targetProductMapper.selectFailureByMydataTradeId(anyLong()))
                .thenReturn(Optional.empty());
    }

    private static CustomerCiHashDTO customer(Long customerId, String ciHash) {
        return CustomerCiHashDTO.builder().customerId(customerId).ciHash(ciHash).build();
    }

    private static MydataTradeResponseDTO trade(
            Long tradeId, String stockType, String fundCode, LocalDate tradeDate) {
        return MydataTradeResponseDTO.builder()
                .tradeId(tradeId)
                .ciHash("ci-1")
                .brokerName("증권사A")
                .tradeType("BUY")
                .stockType(stockType)
                .qty(BigDecimal.TEN)
                .tradeDate(tradeDate)
                .amount(BigDecimal.valueOf(1_000_000))
                .fundCode(fundCode)
                .build();
    }

    @Test
    @DisplayName("동기화 대상 고객이 없으면 mydata 조회, 판정, 커서 갱신 모두 발생하지 않는다")
    void syncAllDoesNothingWhenNoCustomers() {
        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of());

        externalTradeSyncService.syncAll();

        verifyNoInteractions(mydataTradeClient, targetProductService, cursorMapper);
    }

    @Test
    @DisplayName("커서가 없는 고객(첫 동기화)은 fromDate 없이 mydata를 조회한다")
    void firstSyncQueriesWithNullFromDate() {
        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of());

        externalTradeSyncService.syncAll();

        ArgumentCaptor<MydataTradeRequestDTO> captor =
                ArgumentCaptor.forClass(MydataTradeRequestDTO.class);
        verify(mydataTradeClient).getTrades(captor.capture());
        assertThat(captor.getValue().getCiHash()).isEqualTo("ci-1");
        assertThat(captor.getValue().getFromDate()).isNull();
    }

    @Test
    @DisplayName("커서가 있는 고객은 마지막 동기화 거래일을 fromDate로 사용해 조회한다")
    void resumedSyncQueriesWithLastSyncedTradeDate() {
        LocalDate lastSynced = LocalDate.of(2026, 3, 1);
        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L))
                .thenReturn(
                        Optional.of(
                                ExternalTradeSyncCursorDTO.builder()
                                        .customerId(1L)
                                        .lastSyncedTradeDate(lastSynced)
                                        .build()));
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of());

        externalTradeSyncService.syncAll();

        ArgumentCaptor<MydataTradeRequestDTO> captor =
                ArgumentCaptor.forClass(MydataTradeRequestDTO.class);
        verify(mydataTradeClient).getTrades(captor.capture());
        assertThat(captor.getValue().getFromDate()).isEqualTo(lastSynced);
    }

    @Test
    @DisplayName("신규 거래는 각 거래 객체 그대로 judge()에 전달된다")
    void newTradesAreJudgedWithTheirOwnArguments() {
        MydataTradeResponseDTO foreignStock =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO fund = trade(101L, "FUND", "448630", LocalDate.of(2026, 3, 10));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(foreignStock, fund));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(foreignStock);
        verify(targetProductService).judge(fund);
    }

    @Test
    @DisplayName("syncAll()은 신규/스킵 건수와 신규 판정 ID 목록을 결과로 반환한다")
    void syncAllReturnsCountsAndNewJudgementIds() {
        MydataTradeResponseDTO alreadyJudged =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO newTrade1 = trade(101L, "ETF", null, LocalDate.of(2026, 3, 6));
        MydataTradeResponseDTO newTrade2 = trade(102L, "ETN", null, LocalDate.of(2026, 3, 7));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any()))
                .thenReturn(List.of(alreadyJudged, newTrade1, newTrade2));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(true);
        when(targetProductMapper.existsByMydataTradeId(101L)).thenReturn(false);
        when(targetProductMapper.existsByMydataTradeId(102L)).thenReturn(false);
        when(targetProductService.judge(newTrade1))
                .thenReturn(TargetProductJudgementDTO.builder().judgementId(201L).build());
        when(targetProductService.judge(newTrade2))
                .thenReturn(TargetProductJudgementDTO.builder().judgementId(202L).build());

        var result = externalTradeSyncService.syncAll();

        assertThat(result.getCustomerCount()).isEqualTo(1);
        assertThat(result.getFailedCustomerCount()).isEqualTo(0);
        assertThat(result.getNewJudgementCount()).isEqualTo(2);
        assertThat(result.getSkippedJudgementCount()).isEqualTo(1);
        assertThat(result.getNewJudgementIds()).containsExactlyInAnyOrder(201L, 202L);
    }

    @Test
    @DisplayName("이미 판정된 거래(mydata_trade_id 존재)는 judge()를 다시 호출하지 않는다")
    void alreadyJudgedTradeIsSkippedWithoutCallingJudge() {
        MydataTradeResponseDTO alreadyJudged =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO newTrade = trade(101L, "ETF", null, LocalDate.of(2026, 3, 6));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(alreadyJudged, newTrade));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(true);
        when(targetProductMapper.existsByMydataTradeId(101L)).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(newTrade);
        verifyNoMoreInteractions(targetProductService);
    }

    @Test
    @DisplayName("한 거래의 판정이 실패해도 나머지 거래는 계속 처리된다")
    void oneTradeFailureDoesNotStopProcessingOtherTrades() {
        MydataTradeResponseDTO failing = trade(100L, "FUND", "BADCODE", LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO succeeding =
                trade(101L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 6));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(failing, succeeding));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);
        when(targetProductService.judge(failing))
                .thenThrow(new RuntimeException("mydata 펀드 조회 실패"));

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(succeeding);
    }

    @Test
    @DisplayName("고객별 커서를 여러 거래 중 가장 늦은 거래일로 갱신한다")
    void cursorAdvancesToTheLatestTradeDateAmongMultipleTrades() {
        MydataTradeResponseDTO t1 = trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO t2 = trade(101L, "ETF", null, LocalDate.of(2026, 3, 20));
        MydataTradeResponseDTO t3 = trade(102L, "ETN", null, LocalDate.of(2026, 3, 10));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        // 실제 mydata 조회는 trade_date 오름차순으로 오므로(MydataTradeMapper의 ORDER BY trade_date),
        // 테스트 픽스처도 그 순서(3/5 -> 3/10 -> 3/20)에 맞춰 구성한다.
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(t1, t3, t2));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getCustomerId()).isEqualTo(1L);
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    @DisplayName("mydata가 거래를 날짜순이 아니라 뒤섞어서 반환해도, 실제 날짜 기준 최댓값으로 커서가 잡힌다")
    void cursorAdvancesToTheLatestTradeDateEvenWhenMydataReturnsTradesOutOfOrder() {
        MydataTradeResponseDTO latest =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 20));
        MydataTradeResponseDTO earliest = trade(101L, "ETF", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO middle = trade(102L, "ETN", null, LocalDate.of(2026, 3, 10));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        // mydata가 날짜 역순으로 반환하는 상황을 재현 (리스트 마지막 원소가 최댓값이 아님)
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(latest, earliest, middle));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(LocalDate.of(2026, 3, 20));
    }

    @Test
    @DisplayName("뒤섞인 순서로 와도 날짜 기준 실패 지점 이전까지만 커서가 전진한다")
    void cursorStopsAtDateOrderFailurePointEvenWhenMydataReturnsTradesOutOfOrder() {
        MydataTradeResponseDTO late = trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 20));
        MydataTradeResponseDTO failingMiddle =
                trade(101L, "FUND", "BADCODE", LocalDate.of(2026, 3, 10));
        MydataTradeResponseDTO early = trade(102L, "ETF", null, LocalDate.of(2026, 3, 5));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        // 리스트 순서(late -> failingMiddle -> early)는 날짜 순서와 정반대
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(late, failingMiddle, early));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);
        when(targetProductService.judge(late))
                .thenReturn(TargetProductJudgementDTO.builder().build());
        when(targetProductService.judge(failingMiddle))
                .thenThrow(new RuntimeException("mydata 펀드 조회 실패"));
        when(targetProductService.judge(early))
                .thenReturn(TargetProductJudgementDTO.builder().build());

        externalTradeSyncService.syncAll();

        // 날짜순 정렬 후 처리 순서는 early(3/5) -> failingMiddle(3/10, 실패) -> late(3/20)
        // 실패 직전인 3/5에서 커서가 멈춰야 하며, 리스트상 첫 원소였던 3/20으로 잘못 잡히면 안 된다
        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    @DisplayName("유일한 거래가 판정에 실패하면 재시도할 수 있도록 커서를 만들지 않는다")
    void cursorDoesNotAdvancePastAFailingTradeToAllowRetry() {
        MydataTradeResponseDTO failing = trade(100L, "FUND", "BADCODE", LocalDate.of(2026, 3, 20));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(failing));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(false);
        when(targetProductService.judge(failing))
                .thenThrow(new RuntimeException("mydata 펀드 조회 실패"));

        externalTradeSyncService.syncAll();

        // 첫 동기화(fromDate=null)에서 유일한 거래가 실패했으므로 커서를 저장할 근거가 없다
        verify(cursorMapper, never()).upsertCursor(any());
    }

    @Test
    @DisplayName("최초 실패로부터 임계치(3일) 경과한 거래는 재시도 없이 영구실패로 처리하고, 커서 전진과 결과 집계에 반영된다")
    void tradeFailingSinceBeyondThresholdDaysIsPermanentlyFailedWithoutRetryAndCursorAdvances() {
        MydataTradeResponseDTO failing = trade(100L, "FUND", "BADCODE", LocalDate.of(2026, 3, 20));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(failing));
        // 최초 실패로부터 이미 3일 경과 -> judge()를 다시 시도하지 않고 바로 영구실패 처리된다
        when(targetProductMapper.selectFailureByMydataTradeId(100L))
                .thenReturn(
                        Optional.of(
                                TargetProductJudgementFailureDTO.builder()
                                        .mydataTradeId(100L)
                                        .firstFailedAt(TODAY.minusDays(3).atStartOfDay())
                                        .build()));

        var result = externalTradeSyncService.syncAll();

        verify(targetProductService, never()).judge(any());
        verify(targetProductMapper, never()).upsertFailure(any());
        verify(targetProductMapper, never()).existsByMydataTradeId(anyLong());

        // 영구실패 처리라 더 이상 재시도 대상이 아니므로, 커서가 이 거래일까지 전진해야 한다
        ArgumentCaptor<ExternalTradeSyncCursorDTO> cursorCaptor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(cursorCaptor.capture());
        assertThat(cursorCaptor.getValue().getLastSyncedTradeDate())
                .isEqualTo(LocalDate.of(2026, 3, 20));

        assertThat(result.getPermanentlyFailedJudgementCount()).isEqualTo(1);
        assertThat(result.getSkippedJudgementCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("최초 실패로부터 임계치(3일) 미경과면 영구실패 처리하지 않고 judge()를 다시 시도한다")
    void failureWithinThresholdDaysIsStillRetried() {
        MydataTradeResponseDTO failing = trade(100L, "FUND", "BADCODE", LocalDate.of(2026, 3, 20));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(failing));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(false);
        when(targetProductService.judge(failing))
                .thenThrow(new RuntimeException("mydata 펀드 조회 실패"));
        when(targetProductMapper.selectFailureByMydataTradeId(100L))
                .thenReturn(
                        Optional.of(
                                TargetProductJudgementFailureDTO.builder()
                                        .mydataTradeId(100L)
                                        .firstFailedAt(TODAY.minusDays(1).atStartOfDay())
                                        .build()));

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(failing);
        verify(cursorMapper, never()).upsertCursor(any());
    }

    @Test
    @DisplayName("이전에 실패했던 거래가 재시도에서 성공하면 실패 기록을 삭제한다")
    void successAfterPriorFailureDeletesFailureRecord() {
        MydataTradeResponseDTO recovered =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(recovered));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(false);
        when(targetProductMapper.selectFailureByMydataTradeId(100L))
                .thenReturn(
                        Optional.of(
                                TargetProductJudgementFailureDTO.builder()
                                        .mydataTradeId(100L)
                                        .firstFailedAt(TODAY.minusDays(1).atStartOfDay())
                                        .build()));

        externalTradeSyncService.syncAll();

        verify(targetProductMapper).deleteFailureByMydataTradeId(100L);
    }

    @Test
    @DisplayName("실패 이력이 없던 거래가 성공하면 실패 기록 삭제를 호출하지 않는다")
    void successWithoutPriorFailureDoesNotCallDelete() {
        MydataTradeResponseDTO succeeding =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(succeeding));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductMapper, never()).deleteFailureByMydataTradeId(anyLong());
    }

    @Test
    @DisplayName("성공-실패-성공 순서로 거래가 와도 커서는 실패한 거래 이전 지점에서 멈춘다")
    void cursorStopsAtFirstFailureEvenIfLaterTradesSucceed() {
        MydataTradeResponseDTO success1 =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO failing = trade(101L, "FUND", "BADCODE", LocalDate.of(2026, 3, 10));
        MydataTradeResponseDTO success2 = trade(102L, "ETF", null, LocalDate.of(2026, 3, 20));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(success1, failing, success2));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);
        when(targetProductService.judge(success1))
                .thenReturn(TargetProductJudgementDTO.builder().build());
        when(targetProductService.judge(failing))
                .thenThrow(new RuntimeException("mydata 펀드 조회 실패"));
        when(targetProductService.judge(success2))
                .thenReturn(TargetProductJudgementDTO.builder().build());

        externalTradeSyncService.syncAll();

        // 실패한 거래(3/10) 이후의 성공(3/20)은 커서에 반영되지 않고, 실패 직전(3/5)에서 멈춘다
        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(LocalDate.of(2026, 3, 5));

        // 세 거래 모두 스킵되지 않고 judge 시도는 이루어졌다
        verify(targetProductService).judge(success1);
        verify(targetProductService).judge(failing);
        verify(targetProductService).judge(success2);
    }

    @Test
    @DisplayName("신규 거래가 없고 기존 커서도 없으면 커서를 생성하지 않는다")
    void cursorIsNotUpsertedWhenNoTradesAndNoExistingCursor() {
        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of());

        externalTradeSyncService.syncAll();

        verify(cursorMapper, never()).upsertCursor(any());
        verifyNoInteractions(targetProductService);
    }

    @Test
    @DisplayName("신규 거래가 없어도 기존 커서가 있으면 동일한 값으로 커서를 다시 저장한다")
    void cursorIsReupsertedWithSameValueWhenNoNewTrades() {
        LocalDate lastSynced = LocalDate.of(2026, 3, 1);
        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L))
                .thenReturn(
                        Optional.of(
                                ExternalTradeSyncCursorDTO.builder()
                                        .customerId(1L)
                                        .lastSyncedTradeDate(lastSynced)
                                        .build()));
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of());

        externalTradeSyncService.syncAll();

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(lastSynced);
    }

    @Test
    @DisplayName("고객이 여러 명이면 고객마다 각각 mydata를 조회하고 서로의 결과에 영향을 주지 않는다")
    void syncsMultipleCustomersIndependently() {
        MydataTradeResponseDTO customer1Trade =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO customer2Trade =
                MydataTradeResponseDTO.builder()
                        .tradeId(200L)
                        .ciHash("ci-2")
                        .brokerName("증권사A")
                        .tradeType("BUY")
                        .stockType("ETF")
                        .qty(BigDecimal.TEN)
                        .tradeDate(LocalDate.of(2026, 4, 1))
                        .amount(BigDecimal.valueOf(1_000_000))
                        .build();

        when(customerMapper.selectActiveRiaCustomers())
                .thenReturn(List.of(customer(1L, "ci-1"), customer(2L, "ci-2")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(cursorMapper.selectByCustomerId(2L)).thenReturn(Optional.empty());
        // customerMapper가 고객을 1번→2번 순서로 반환하고 syncAll()도 그 순서로 순회하므로,
        // getTrades() 호출 순서에 맞춰 결과를 순차적으로 반환하도록 스텁한다.
        when(mydataTradeClient.getTrades(any()))
                .thenReturn(List.of(customer1Trade))
                .thenReturn(List.of(customer2Trade));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(customer1Trade);
        verify(targetProductService).judge(customer2Trade);
        verify(mydataTradeClient, times(2)).getTrades(any());

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper, times(2)).upsertCursor(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(
                        ExternalTradeSyncCursorDTO::getCustomerId,
                        ExternalTradeSyncCursorDTO::getLastSyncedTradeDate)
                .containsExactlyInAnyOrder(
                        tuple(1L, LocalDate.of(2026, 3, 5)), tuple(2L, LocalDate.of(2026, 4, 1)));
    }

    @Test
    @DisplayName("시스템 시간(오늘) 이후 거래는 판정하지 않고, 커서도 그 이전 거래에서 멈춘다")
    void syncCustomerExcludesTradesAfterSystemToday() {
        MydataTradeResponseDTO past = trade(100L, "FOREIGN_STOCK", null, TODAY.minusDays(5));
        MydataTradeResponseDTO future = trade(101L, "FOREIGN_STOCK", null, TODAY.plusDays(1));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(past, future));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(past);
        verify(targetProductService, never()).judge(future);

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(TODAY.minusDays(5));
    }

    @Test
    @DisplayName("거래일이 시스템 시간과 정확히 같으면 필터링되지 않고 판정된다")
    void syncCustomerIncludesTradeExactlyOnSystemToday() {
        MydataTradeResponseDTO onToday = trade(100L, "FOREIGN_STOCK", null, TODAY);

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(onToday));
        when(targetProductMapper.existsByMydataTradeId(anyLong())).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(onToday);
    }

    @Test
    @DisplayName("모든 거래가 시스템 시간 이후면 아무것도 판정하지 않고 커서도 만들지 않는다")
    void syncCustomerUpsertsNoCursorWhenAllTradesAreAfterSystemToday() {
        MydataTradeResponseDTO future = trade(100L, "FOREIGN_STOCK", null, TODAY.plusDays(3));

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(future));

        externalTradeSyncService.syncAll();

        verifyNoInteractions(targetProductService);
        verify(cursorMapper, never()).upsertCursor(any());
    }

    @Test
    @DisplayName("mydata 응답의 ciHash가 요청한 고객과 다르면 해당 거래는 판정하지 않고 스킵한다")
    void judgeSkipsTradeWhenMydataResponseCiHashDoesNotMatchRequestedCustomer() {
        MydataTradeResponseDTO mismatched =
                MydataTradeResponseDTO.builder()
                        .tradeId(100L)
                        .ciHash("ci-other-customer")
                        .brokerName("증권사A")
                        .tradeType("BUY")
                        .stockType("FOREIGN_STOCK")
                        .qty(BigDecimal.TEN)
                        .tradeDate(LocalDate.of(2026, 3, 5))
                        .amount(BigDecimal.valueOf(1_000_000))
                        .build();

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(mismatched));

        externalTradeSyncService.syncAll();

        verifyNoInteractions(targetProductService);
        verify(cursorMapper, never()).upsertCursor(any());
    }

    @Test
    @DisplayName("ciHash가 일치하는 거래와 불일치하는 거래가 섞여 있으면 일치하는 거래만 판정한다")
    void judgeProcessesOnlyMatchingCiHashTradesWhenMixedWithMismatched() {
        MydataTradeResponseDTO matching =
                trade(100L, "FOREIGN_STOCK", null, LocalDate.of(2026, 3, 5));
        MydataTradeResponseDTO mismatched =
                MydataTradeResponseDTO.builder()
                        .tradeId(101L)
                        .ciHash("ci-other-customer")
                        .brokerName("증권사A")
                        .tradeType("BUY")
                        .stockType("FOREIGN_STOCK")
                        .qty(BigDecimal.TEN)
                        .tradeDate(LocalDate.of(2026, 3, 6))
                        .amount(BigDecimal.valueOf(1_000_000))
                        .build();

        when(customerMapper.selectActiveRiaCustomers()).thenReturn(List.of(customer(1L, "ci-1")));
        when(cursorMapper.selectByCustomerId(1L)).thenReturn(Optional.empty());
        when(mydataTradeClient.getTrades(any())).thenReturn(List.of(matching, mismatched));
        when(targetProductMapper.existsByMydataTradeId(100L)).thenReturn(false);

        externalTradeSyncService.syncAll();

        verify(targetProductService).judge(matching);
        verify(targetProductService, never()).judge(mismatched);

        ArgumentCaptor<ExternalTradeSyncCursorDTO> captor =
                ArgumentCaptor.forClass(ExternalTradeSyncCursorDTO.class);
        verify(cursorMapper).upsertCursor(captor.capture());
        assertThat(captor.getValue().getLastSyncedTradeDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    }
}
