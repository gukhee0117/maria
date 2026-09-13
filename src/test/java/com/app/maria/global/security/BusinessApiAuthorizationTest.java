package com.app.maria.global.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.app.maria.domain.account.dto.request.ReasonRequestDTO;
import com.app.maria.domain.accountclosure.api.AccountClosureApi;
import com.app.maria.domain.accountclosure.service.AccountClosureService;
import com.app.maria.domain.withdrawal.api.WithdrawalApi;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.service.WithdrawalQueryService;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class BusinessApiAuthorizationTest {

    private final WithdrawalService withdrawalService = mock(WithdrawalService.class);
    private final AccountClosureService accountClosureService = mock(AccountClosureService.class);

    private final WithdrawalApi withdrawalApi =
            securedProxy(new WithdrawalApi(mock(WithdrawalQueryService.class), withdrawalService));

    private final AccountClosureApi accountClosureApi =
            securedProxy(new AccountClosureApi(accountClosureService));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewerCannotExecuteWithdrawal() {
        authenticateAs("VIEWER");
        WithdrawalRequestDTO request =
                WithdrawalRequestDTO.builder()
                        .accountId(1L)
                        .requestedAmount(BigDecimal.valueOf(1_000))
                        .earlyWithdrawalAgreed(false)
                        .destinationGeneralAccountId(10L)
                        .build();

        assertThatThrownBy(() -> withdrawalApi.withdraw(request))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(withdrawalService);
    }

    @Test
    void adminCannotApproveOrRejectClosure() {
        authenticateAs("ADMIN");
        assertThatThrownBy(() -> accountClosureApi.approveClosure(1L, 10L))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> accountClosureApi.rejectClosure(1L, 10L, null))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(accountClosureService);
    }

    @Test
    void reviewerCanApproveAndRejectClosure() {
        authenticateAs("REVIEWER");
        ReasonRequestDTO request = ReasonRequestDTO.builder().reason("반려 사유").build();

        assertThatCode(() -> accountClosureApi.approveClosure(1L, 10L)).doesNotThrowAnyException();
        assertThatCode(() -> accountClosureApi.rejectClosure(1L, 10L, request))
                .doesNotThrowAnyException();
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                1L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static <T> T securedProxy(T target) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvisor(
                AuthorizationManagerBeforeMethodInterceptor.preAuthorize(
                        new PreAuthorizeAuthorizationManager()));
        @SuppressWarnings("unchecked")
        T proxy = (T) proxyFactory.getProxy();
        return proxy;
    }
}
