package com.app.maria.domain.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.app.maria.domain.admin.dto.AdminUserDTO;
import com.app.maria.domain.admin.dto.request.AdminLoginRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminLoginResponseDTO;
import com.app.maria.domain.admin.dto.response.AdminSummaryResponseDTO;
import com.app.maria.domain.admin.mapper.AdminMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.domain.admin.type.AdminRole;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock AdminMapper adminMapper;

    @Mock PasswordEncoder passwordEncoder;

    @Mock JwtTokenProvider jwtTokenProvider;

    @Mock Claims claims;

    @Mock AuditLogService auditLogService;

    @InjectMocks AdminServiceImpl adminService;

    private AdminUserDTO admin() {
        return AdminUserDTO.builder()
                .adminId(1L)
                .loginId("reviewer1")
                .name("이은정")
                .passwordHash("encoded-password")
                .role(AdminRole.REVIEWER)
                .build();
    }

    @Test
    @DisplayName("정상 요청이면 토큰을 발급한다")
    void loginIssuesTokensOnSuccess() {
        AdminLoginRequestDTO request =
                AdminLoginRequestDTO.builder()
                        .loginId("reviewer1")
                        .password("raw-password")
                        .build();

        when(adminMapper.selectAdminByLoginId("reviewer1")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(1L, "reviewer1", "이은정", AdminRole.REVIEWER))
                .thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(1L)).thenReturn("refresh-token");

        AdminLoginResponseDTO result = adminService.login(request);

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("존재하지 않는 아이디면 예외를 던지고 토큰을 발급하지 않는다")
    void loginThrowsWhenLoginIdNotFound() {
        AdminLoginRequestDTO request =
                AdminLoginRequestDTO.builder().loginId("nobody").password("raw-password").build();

        when(adminMapper.selectAdminByLoginId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.login(request))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_LOGIN_FAILED.getMessage());

        verifyNoInteractions(passwordEncoder, jwtTokenProvider);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 예외를 던지고 토큰을 발급하지 않는다")
    void loginThrowsWhenPasswordIncorrect() {
        AdminLoginRequestDTO request =
                AdminLoginRequestDTO.builder()
                        .loginId("reviewer1")
                        .password("wrong-password")
                        .build();

        when(adminMapper.selectAdminByLoginId("reviewer1")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> adminService.login(request))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_LOGIN_FAILED.getMessage());

        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    @DisplayName("아이디 없음과 비밀번호 틀림의 에러 메시지가 동일하다")
    void loginReturnsSameMessageForMissingIdAndWrongPassword() {
        AdminLoginRequestDTO noSuchUser =
                AdminLoginRequestDTO.builder().loginId("nobody").password("x").build();
        AdminLoginRequestDTO wrongPassword =
                AdminLoginRequestDTO.builder().loginId("reviewer1").password("wrong").build();

        when(adminMapper.selectAdminByLoginId("nobody")).thenReturn(Optional.empty());
        when(adminMapper.selectAdminByLoginId("reviewer1")).thenReturn(Optional.of(admin()));
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        String messageForMissingUser =
                catchAppExceptionMessage(() -> adminService.login(noSuchUser));
        String messageForWrongPassword =
                catchAppExceptionMessage(() -> adminService.login(wrongPassword));

        assertThat(messageForMissingUser).isEqualTo(messageForWrongPassword);
    }

    @Test
    @DisplayName("대상 관리자가 존재하면 역할을 변경하고 감사로그를 남긴다")
    void updateRoleUpdatesRoleWhenAdminExists() {
        when(adminMapper.selectAdminByAdminId(1L)).thenReturn(Optional.of(admin()));

        adminService.updateRole(99L, 1L, AdminRole.ADMIN);

        verify(adminMapper).updateRole(1L, AdminRole.ADMIN);

        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        AuditLogDTO auditLog = captor.getValue();
        assertThat(auditLog.getAdminId()).isEqualTo(99L);
        assertThat(auditLog.getTargetTable()).isEqualTo("ADMIN_USER");
        assertThat(auditLog.getTargetPk()).isEqualTo("1");
        assertThat(auditLog.getBeforeValue()).isEqualTo("REVIEWER");
        assertThat(auditLog.getAfterValue()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("대상 관리자가 없으면 예외를 던지고 역할 변경도 감사로그 저장도 하지 않는다")
    void updateRoleThrowsAdminNotFoundExceptionWhenAdminDoesNotExist() {
        when(adminMapper.selectAdminByAdminId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.updateRole(99L, 1L, AdminRole.ADMIN))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_NOT_FOUND.getMessage());

        verify(adminMapper, never()).updateRole(anyLong(), any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("관리자 목록을 요약 DTO 리스트로 변환해 반환한다")
    void getAllAdminsReturnsMappedSummaryList() {
        AdminUserDTO reviewer = admin();
        AdminUserDTO admin =
                AdminUserDTO.builder()
                        .adminId(2L)
                        .loginId("admin1")
                        .name("천유진")
                        .passwordHash("encoded-password-2")
                        .role(AdminRole.ADMIN)
                        .build();
        when(adminMapper.selectAllAdmins()).thenReturn(List.of(reviewer, admin));

        List<AdminSummaryResponseDTO> result = adminService.getAllAdmins();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAdminId()).isEqualTo(1L);
        assertThat(result.get(0).getLoginId()).isEqualTo("reviewer1");
        assertThat(result.get(0).getRole()).isEqualTo(AdminRole.REVIEWER);
        assertThat(result.get(0).getName()).isEqualTo("이은정");
        assertThat(result.get(1).getAdminId()).isEqualTo(2L);
        assertThat(result.get(1).getRole()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    @DisplayName("등록된 관리자가 없으면 빈 리스트를 반환한다")
    void getAllAdminsReturnsEmptyListWhenNoAdminsExist() {
        when(adminMapper.selectAllAdmins()).thenReturn(List.of());

        List<AdminSummaryResponseDTO> result = adminService.getAllAdmins();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("유효한 refreshToken이면 최신 role로 새 accessToken을 발급한다")
    void refreshIssuesNewAccessTokenUsingCurrentDatabaseRole() {
        when(jwtTokenProvider.parseClaims("valid-refresh-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("1");
        when(adminMapper.selectAdminByAdminId(1L)).thenReturn(Optional.of(admin()));
        when(jwtTokenProvider.createAccessToken(1L, "reviewer1", "이은정", AdminRole.REVIEWER))
                .thenReturn("new-access-token");

        AdminLoginResponseDTO result = adminService.refresh("valid-refresh-token");

        assertThat(result.getAccessToken()).isEqualTo("new-access-token");
        assertThat(result.getRefreshToken()).isEqualTo("valid-refresh-token");
        verify(adminMapper).selectAdminByAdminId(1L);
        verify(jwtTokenProvider).createAccessToken(1L, "reviewer1", "이은정", AdminRole.REVIEWER);
    }

    @Test
    @DisplayName("토큰이 위조/만료되었으면 예외를 던지고 DB를 조회하지 않는다")
    void refreshThrowsAdminExceptionWhenTokenInvalid() {
        when(jwtTokenProvider.parseClaims("broken-token")).thenThrow(new JwtException("bad token"));

        assertThatThrownBy(() -> adminService.refresh("broken-token"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_TOKEN_INVALID.getMessage());

        verifyNoInteractions(adminMapper);
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 예외를 던지고 DB를 조회하지 않는다")
    void refreshThrowsAdminExceptionWhenSubjectIsNotNumeric() {
        when(jwtTokenProvider.parseClaims("weird-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("not-a-number");

        assertThatThrownBy(() -> adminService.refresh("weird-token"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_TOKEN_SUBJECT_INVALID.getMessage());

        verifyNoInteractions(adminMapper);
    }

    @Test
    @DisplayName("토큰의 대상 관리자가 없으면 예외를 던지고 accessToken을 발급하지 않는다")
    void refreshThrowsAdminNotFoundExceptionWhenAdminDoesNotExist() {
        when(jwtTokenProvider.parseClaims("valid-refresh-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("1");
        when(adminMapper.selectAdminByAdminId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.refresh("valid-refresh-token"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ADMIN_NOT_FOUND.getMessage());

        verify(jwtTokenProvider, never()).createAccessToken(any(), any(), any(), any());
    }

    private String catchAppExceptionMessage(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e.getMessage();
        }
        throw new AssertionError("AppException expected but not thrown");
    }
}
