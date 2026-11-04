package com.app.maria.domain.sellorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.mapper.CustomerSellOrderMapper;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerSellOrderServiceImplTest {

    @Mock AccountMapper accountMapper;
    @Mock CustomerSellOrderMapper customerSellOrderMapper;
    @InjectMocks CustomerSellOrderServiceImpl service;

    private void stubAccount() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(100L).build()));
    }

    @Test
    @DisplayName("본인 계좌의 매도 내역을 페이지 정보와 함께 준다")
    void returnsPageOfOwnAccount() {
        stubAccount();
        CustomerSellOrderResponseDTO row = new CustomerSellOrderResponseDTO();
        when(customerSellOrderMapper.selectByAccountId(100L, 40L, 20)).thenReturn(List.of(row));
        when(customerSellOrderMapper.countByAccountId(100L)).thenReturn(41);

        PageResponseDTO<CustomerSellOrderResponseDTO> result = service.getSellOrders(7L, 2, 20);

        assertThat(result.getContent()).containsExactly(row);
        assertThat(result.getTotalCount()).isEqualTo(41);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("size는 1~100으로, page는 0 이상으로 보정한다")
    void clampsPageAndSize() {
        stubAccount();
        when(customerSellOrderMapper.selectByAccountId(100L, 0L, 100)).thenReturn(List.of());
        when(customerSellOrderMapper.selectByAccountId(100L, 0L, 1)).thenReturn(List.of());

        assertThat(service.getSellOrders(7L, -5, 100000).getSize()).isEqualTo(100);
        assertThat(service.getSellOrders(7L, -5, 0).getSize()).isEqualTo(1);
        verify(customerSellOrderMapper).selectByAccountId(100L, 0L, 100);
        verify(customerSellOrderMapper).selectByAccountId(100L, 0L, 1);
    }

    @Test
    @DisplayName("RIA 계좌가 없으면 ACCOUNT_NOT_FOUND다")
    void rejectsCustomerWithoutAccount() {
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSellOrders(7L, 0, 20))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
        verifyNoInteractions(customerSellOrderMapper);
    }

    @Test
    @DisplayName("page가 아주 커도 offset이 int 오버플로로 음수가 되지 않는다")
    void offsetDoesNotOverflow() {
        stubAccount();
        when(customerSellOrderMapper.selectByAccountId(100L, (long) Integer.MAX_VALUE * 100, 100))
                .thenReturn(List.of());

        assertThat(service.getSellOrders(7L, Integer.MAX_VALUE, 100).getContent()).isEmpty();
        verify(customerSellOrderMapper)
                .selectByAccountId(100L, (long) Integer.MAX_VALUE * 100, 100);
    }
}
