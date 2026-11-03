package com.app.maria.domain.community.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.dto.response.AdminReportProcessResponseDTO;
import com.app.maria.domain.community.service.CommunityReportAdminService;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.ReportAction;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommunityReportAdminApi.class)
@Import(SecurityConfig.class)
class CommunityReportAdminApiTest {
    private static final String URL = "/api/admin/community/reports";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityReportAdminService adminService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "REVIEWER", "VIEWER"})
    @DisplayName("목록은 ADMIN·REVIEWER·VIEWER가 볼 수 있다")
    void listAllowed(String role) throws Exception {
        when(adminService.list(ReportStatus.PENDING, 30)).thenReturn(List.of());

        mockMvc.perform(get(URL).with(user("admin").roles(role))).andExpect(status().isOk());

        verify(adminService).list(ReportStatus.PENDING, 30);
    }

    @Test
    @DisplayName("SETTLEMENT 역할이나 로그인하지 않은 요청은 목록을 볼 수 없다")
    void listDenied() throws Exception {
        mockMvc.perform(get(URL).with(user("admin").roles("SETTLEMENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL)).andExpect(status().is4xxClientError());
        verifyNoInteractions(adminService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "REVIEWER"})
    @DisplayName("처리는 ADMIN·REVIEWER만 할 수 있다")
    void processAllowed(String role) throws Exception {
        when(adminService.process(5L, ReportAction.HIDE))
                .thenReturn(
                        new AdminReportProcessResponseDTO("POST", 11L, CommunityStatus.HIDDEN, 2));

        mockMvc.perform(
                        put(URL + "/5")
                                .with(user("admin").roles(role))
                                .contentType(APPLICATION_JSON)
                                .content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.data.processedReportCount").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "SETTLEMENT"})
    @DisplayName("VIEWER·SETTLEMENT는 처리할 수 없고 서비스를 부르지 않는다")
    void processDenied(String role) throws Exception {
        mockMvc.perform(
                        put(URL + "/5")
                                .with(user("admin").roles(role))
                                .contentType(APPLICATION_JSON)
                                .content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isForbidden());
        verify(adminService, never()).process(any(), any());
    }

    @Test
    @DisplayName("처리 방법이 없거나 모르는 값이거나 번호가 0 이하이면 400이고, 이미 처리했으면 409다")
    void validationAndErrors() throws Exception {
        mockMvc.perform(
                        put(URL + "/5")
                                .with(user("admin").roles("ADMIN"))
                                .contentType(APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        put(URL + "/5")
                                .with(user("admin").roles("ADMIN"))
                                .contentType(APPLICATION_JSON)
                                .content("{\"action\":\"BAN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        put(URL + "/0")
                                .with(user("admin").roles("ADMIN"))
                                .contentType(APPLICATION_JSON)
                                .content("{\"action\":\"HIDE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).with(user("admin").roles("ADMIN")).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).with(user("admin").roles("ADMIN")).param("status", "WHATEVER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminService);

        when(adminService.process(6L, ReportAction.RESTORE))
                .thenThrow(new AppException(ErrorType.COMMUNITY_REPORT_ALREADY_PROCESSED));
        mockMvc.perform(
                        put(URL + "/6")
                                .with(user("admin").roles("ADMIN"))
                                .contentType(APPLICATION_JSON)
                                .content("{\"action\":\"RESTORE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_REPORT_ALREADY_PROCESSED"));
    }
}
