package com.app.maria.global.audit.dto.request;

import com.app.maria.global.audit.dto.AuditLogSearchDTO;
import com.app.maria.global.sort.OrderBySpec;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class AuditLogSearchRequestDTO {

    private static final Map<String, String> REASON_CODE_LABELS =
            Map.ofEntries(
                    Map.entry("ADMIN_ROLE_UPDATE", "관리자 권한 변경"),
                    Map.entry("ACCOUNT_APPLY", "계좌 개설 신청"),
                    Map.entry("ACCOUNT_REAPPLY", "계좌 재신청"),
                    Map.entry("ACCOUNT_CHANGE_LIMIT_AMOUNT", "한도 변경"),
                    Map.entry("ACCOUNT_OPENED", "계좌 개설"),
                    Map.entry("ACCOUNT_REJECTED", "계좌 반려"),
                    Map.entry("ACCOUNT_OVERRIDE_OPENED", "계좌 오버라이드 개설"),
                    Map.entry("ACCOUNT_CLOSURE_REQUESTED", "계좌 해지 신청"),
                    Map.entry("ACCOUNT_CLOSURE_APPROVED", "계좌 해지 승인"),
                    Map.entry("ACCOUNT_CLOSURE_REJECTED", "계좌 해지 반려"),
                    Map.entry("SETTLEMENT_BATCH_REQUESTED", "정산 배치 실행 요청"),
                    Map.entry("SETTLEMENT_BATCH_RETRIED", "정산 배치 재처리"),
                    Map.entry("SETTLEMENT_ITEM_RETRIED", "정산 항목 재처리"));

    private static final Map<String, String> ROLE_LABELS =
            Map.of(
                    "VIEWER", "조회전용",
                    "REVIEWER", "심사담당",
                    "SETTLEMENT", "정산담당",
                    "ADMIN", "최고관리자");

    private String targetTable;
    private String adminKeyword;
    private String targetKeyword;
    private String reasonKeyword;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    private String sort;
    private String direction;

    /** 감사 로그 목록의 정렬 키(화면 머리글) → 정렬 열. 여기 없는 키는 무시한다. */
    private static final Map<String, String> SORT_COLUMNS =
            Map.of(
                    "processedAt", "al.processed_at",
                    "recordedAt", "al.recorded_at",
                    "adminName", "au.name",
                    "reasonCode", "al.reason_code",
                    "targetTable", "al.target_table",
                    "afterValue", "al.after_value");

    @AssertTrue(message = "시작일은 종료일보다 늦을 수 없습니다.")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !startDate.isAfter(endDate);
    }

    @Min(0)
    @Max(1_000_000)
    @Builder.Default
    private int page = 0;

    @Min(1)
    @Max(100)
    @Builder.Default
    private int size = 20;

    private List<String> matchLabels(String kw, Map<String, String> labels) {
        if (kw == null || kw.isBlank()) {
            return List.of();
        }
        return labels.entrySet().stream()
                .filter(entry -> entry.getValue().contains(kw))
                .map(Map.Entry::getKey)
                .toList();
    }

    public AuditLogSearchDTO toAuditLogSearchDTO() {
        return AuditLogSearchDTO.builder()
                .targetTable(targetTable)
                .adminKeyword(adminKeyword)
                .matchedRoles(matchLabels(adminKeyword, ROLE_LABELS))
                .targetKeyword(targetKeyword)
                .reasonKeyword(reasonKeyword)
                .matchedReasonCodes(matchLabels(reasonKeyword, REASON_CODE_LABELS))
                .knownReasonCodes(List.copyOf(REASON_CODE_LABELS.keySet()))
                .startDate(startDate)
                .endDate(endDate)
                .orderBy(
                        OrderBySpec.resolve(
                                SORT_COLUMNS,
                                sort,
                                direction,
                                "al.processed_at DESC, al.audit_id DESC"))
                .size(size)
                .offset(page * size)
                .build();
    }
}
