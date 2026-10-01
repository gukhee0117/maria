package com.app.maria.global.clock.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.clock.service.SystemClockManagementService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SystemClockApiTest {

    @Mock private SystemClockManagementService systemClockManagementService;

    @Mock private BusinessClockService businessClockService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SystemClockApi systemClockApi =
                new SystemClockApi(systemClockManagementService, businessClockService);

        mockMvc =
                MockMvcBuilders.standaloneSetup(systemClockApi)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setMessageConverters(
                                new MappingJackson2HttpMessageConverter(
                                        Jackson2ObjectMapperBuilder.json()
                                                .featuresToDisable(
                                                        SerializationFeature
                                                                .WRITE_DATES_AS_TIMESTAMPS)
                                                .build()))
                        .build();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                4L, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 현재_MARIA_업무시각을_조회한다() throws Exception {
        LocalDateTime currentDatetime = LocalDateTime.of(2027, 8, 5, 9, 30);

        when(businessClockService.now()).thenReturn(currentDatetime);

        mockMvc.perform(get("/api/admin/system-clock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("시스템 업무시각 조회 완료"))
                .andExpect(jsonPath("$.data").value("2027-08-05T09:30:00"));

        verify(businessClockService).now();
        verifyNoInteractions(systemClockManagementService);
    }

    @Test
    void MARIA_업무시각을_변경한다() throws Exception {
        LocalDateTime changedDatetime = LocalDateTime.of(2027, 8, 5, 9, 30);

        when(systemClockManagementService.changeSystemTime(
                        eq(4L), any(SystemClockChangeRequestDTO.class)))
                .thenReturn(changedDatetime);

        mockMvc.perform(
                        patch("/api/admin/system-clock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                  {
                                    "newDatetime": "2027-08-05T09:30:00",
                                    "reasonCode": "DEMO_TIME_CHANGE"
                                  }
                                  """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("시스템 업무시각 변경 완료"))
                .andExpect(jsonPath("$.data").value("2027-08-05T09:30:00"));

        ArgumentCaptor<SystemClockChangeRequestDTO> requestCaptor =
                ArgumentCaptor.forClass(SystemClockChangeRequestDTO.class);

        verify(systemClockManagementService).changeSystemTime(eq(4L), requestCaptor.capture());

        SystemClockChangeRequestDTO requestDTO = requestCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(requestDTO.getNewDatetime())
                .isEqualTo(changedDatetime);
        org.assertj.core.api.Assertions.assertThat(requestDTO.getReasonCode())
                .isEqualTo("DEMO_TIME_CHANGE");

        verifyNoInteractions(businessClockService);
    }

    @Test
    void 변경할_시각과_사유가_없으면_변경하지_않는다() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/system-clock")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                  {
                                    "reasonCode": ""
                                  }
                                  """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(systemClockManagementService, businessClockService);
    }

    @Test
    void 시스템_Clock이_초기화되지_않으면_조회_API는_500을_반환한다() throws Exception {
        when(businessClockService.now())
                .thenThrow(new AppException(ErrorType.SYSTEM_CLOCK_NOT_INITIALIZED));

        mockMvc.perform(get("/api/admin/system-clock"))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        jsonPath("$.message")
                                .value(ErrorType.SYSTEM_CLOCK_NOT_INITIALIZED.getMessage()));

        verify(businessClockService).now();
        verifyNoInteractions(systemClockManagementService);
    }

    @Test
    void 다른_관리자가_먼저_시간을_변경하면_변경_API는_409를_반환한다() throws Exception {
        when(systemClockManagementService.changeSystemTime(
                        eq(4L), any(SystemClockChangeRequestDTO.class)))
                .thenThrow(new AppException(ErrorType.SYSTEM_CLOCK_UPDATE_CONFLICT));

        mockMvc.perform(clockChangeRequest())
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.message")
                                .value(ErrorType.SYSTEM_CLOCK_UPDATE_CONFLICT.getMessage()));

        verify(systemClockManagementService)
                .changeSystemTime(eq(4L), any(SystemClockChangeRequestDTO.class));
        verifyNoInteractions(businessClockService);
    }

    @Test
    void 감사로그_저장에_실패하면_변경_API는_500을_반환한다() throws Exception {
        when(systemClockManagementService.changeSystemTime(
                        eq(4L), any(SystemClockChangeRequestDTO.class)))
                .thenThrow(new AppException(ErrorType.AUDIT_LOG_INSERT_FAILED));

        mockMvc.perform(clockChangeRequest())
                .andExpect(status().isInternalServerError())
                .andExpect(
                        jsonPath("$.message")
                                .value(ErrorType.AUDIT_LOG_INSERT_FAILED.getMessage()));

        verify(systemClockManagementService)
                .changeSystemTime(eq(4L), any(SystemClockChangeRequestDTO.class));
        verifyNoInteractions(businessClockService);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            clockChangeRequest() {
        return patch("/api/admin/system-clock")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                        {
                          "newDatetime": "2027-08-05T09:30:00",
                          "reasonCode": "DEMO_TIME_CHANGE"
                        }
                        """);
    }
}
