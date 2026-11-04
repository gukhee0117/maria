package com.app.maria.domain.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class AccountChatToolTest {

    @Mock AccountMapper accountMapper;
    @InjectMocks AccountChatTool tool;

    @Test
    @DisplayName("계좌가 있으면 예수금·상태·혜택을 반환한다")
    void returnsAccountInfo_whenAccountExists() {
        AccountDTO dto = new AccountDTO();
        dto.setAmount(new BigDecimal("3200000"));
        dto.setStatus(Status.OPENED);
        dto.setBenefit(BenefitType.POSSIBLE);
        when(accountMapper.selectByCustomerId(1L)).thenReturn(Optional.of(dto));

        String result = tool.getAccountSummary(new ToolContext(Map.of("customerId", 1L)));

        assertThat(result).contains("3,200,000").contains("OPENED").contains("POSSIBLE");
    }

    @Test
    @DisplayName("계좌가 없으면 조회 실패 메시지를 반환한다")
    void returnsFailMessage_whenNoAccount() {
        when(accountMapper.selectByCustomerId(99L)).thenReturn(Optional.empty());

        String result = tool.getAccountSummary(new ToolContext(Map.of("customerId", 99L)));

        assertThat(result).contains("조회 실패");
    }

    @Test
    @DisplayName("ToolContext에 customerId가 없으면 조회 실패 메시지를 반환한다")
    void returnsFailMessage_whenNoCustomerId() {
        String result = tool.getAccountSummary(new ToolContext(Map.of()));

        assertThat(result).contains("조회 실패");
    }
}
