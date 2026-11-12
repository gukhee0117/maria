package com.app.maria.global.clock.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.clock.service.SystemClockManagementService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** 업무시각 변경 권한을 실제 메서드 보안(@PreAuthorize)이 켜진 상태로 확인한다. */
@SpringJUnitConfig(SystemClockApiSecurityTest.Config.class)
class SystemClockApiSecurityTest {
    private static final LocalDateTime CHANGED = LocalDateTime.of(2027, 8, 5, 9, 30);

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        SystemClockManagementService systemClockManagementService() {
            SystemClockManagementService service = mock(SystemClockManagementService.class);
            when(service.changeSystemTime(any(), any())).thenReturn(CHANGED);
            return service;
        }

        @Bean
        BusinessClockService businessClockService() {
            return mock(BusinessClockService.class);
        }

        @Bean
        SystemClockApi systemClockApi(
                SystemClockManagementService service, BusinessClockService clock) {
            return new SystemClockApi(service, clock);
        }
    }

    @Autowired private SystemClockApi api;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                4L, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static SystemClockChangeRequestDTO request() {
        return new SystemClockChangeRequestDTO(CHANGED, "DEMO_TIME_CHANGE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SETTLEMENT"})
    void 최고관리자와_정산담당은_업무시각을_바꿀_수_있다(String role) {
        loginAs(role);

        assertThat(api.changeSystemTime(4L, request()).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEWER", "VIEWER"})
    void 심사담당과_조회전용은_업무시각을_바꿀_수_없다(String role) {
        loginAs(role);

        assertThatThrownBy(() -> api.changeSystemTime(4L, request()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SETTLEMENT", "REVIEWER", "VIEWER"})
    void 업무시각_조회는_모든_관리자가_할_수_있다(String role) {
        loginAs(role);

        assertThat(api.getSystemTime().getStatusCode().is2xxSuccessful()).isTrue();
    }
}
