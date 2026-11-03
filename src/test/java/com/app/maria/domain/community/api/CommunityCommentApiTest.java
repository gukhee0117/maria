package com.app.maria.domain.community.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.dto.request.CommentWriteRequestDTO;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.service.CommunityCommentService;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CommunityCommentApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CommunityCommentApiTest {
    private static final String BASE = "/api/customer/community";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityCommentService commentService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static CommentResponseDTO sample() {
        return new CommentResponseDTO(
                31L,
                11L,
                AuthorType.CUSTOMER,
                "장기보유러",
                null,
                true,
                false,
                "댓글",
                CommunityStatus.VISIBLE,
                0,
                false,
                30L,
                "반도체개미",
                null,
                null,
                LocalDateTime.of(2026, 10, 29, 15, 0));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, CSRF 없이 쓰기·삭제하면 403이고 서비스를 부르지 않는다")
    void authAndCsrf() throws Exception {
        mockMvc.perform(get(BASE + "/posts/11/comments")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post(BASE + "/posts/11/comments")
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post(BASE + "/posts/11/comments")
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/comments/31").with(customerAuth()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("댓글을 쓰면 로그인한 고객 ID로 서비스를 부르고 replyToCommentId를 그대로 넘기며 본문의 customerId는 무시한다")
    void write() throws Exception {
        when(commentService.write(eq(7L), eq(11L), any())).thenReturn(sample());

        mockMvc.perform(
                        post(BASE + "/posts/11/comments")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"customerId\":999,\"authorType\":\"AI\",\"content\":\"@반도체개미 맞아요\",\"replyToCommentId\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(31))
                .andExpect(jsonPath("$.data.replyToNickname").value("반도체개미"));

        ArgumentCaptor<CommentWriteRequestDTO> captor =
                ArgumentCaptor.forClass(CommentWriteRequestDTO.class);
        verify(commentService).write(eq(7L), eq(11L), captor.capture());
        assertThat(captor.getValue().replyToCommentId()).isEqualTo(30L);
        assertThat(captor.getValue().content()).isEqualTo("@반도체개미 맞아요");
    }

    @Test
    @DisplayName("응답 JSON에는 customerId가 없다")
    void noCustomerId() throws Exception {
        when(commentService.list(eq(7L), eq(11L), isNull(), anyInt()))
                .thenReturn(new CommentPageResponseDTO(List.of(sample()), null));

        String body =
                mockMvc.perform(get(BASE + "/posts/11/comments").with(customerAuth()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("customerId");
        assertThat(body).contains("authorNickname");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/posts/0/comments",
                "/posts/-1/comments",
                "/posts/11/comments?size=0",
                "/posts/11/comments?size=51",
                "/posts/11/comments?cursor=-1"
            })
    @DisplayName("범위를 벗어난 경로·쿼리 값은 400이다")
    void listValidation(String path) throws Exception {
        mockMvc.perform(get(BASE + path).with(customerAuth())).andExpect(status().isBadRequest());
        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("본문이 없거나 2000자를 넘거나 답할 댓글 번호가 0 이하면 400이다")
    void writeValidation() throws Exception {
        for (String body :
                List.of(
                        "{}",
                        "{\"content\":\"" + "가".repeat(2001) + "\"}",
                        "{\"content\":\"x\",\"replyToCommentId\":0}",
                        "{\"content\":\"x\",\"replyToCommentId\":-4}")) {
            mockMvc.perform(
                            post(BASE + "/posts/11/comments")
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(
                        post(BASE + "/posts/0/comments")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("목록 기본 크기는 20이고 cursor를 그대로 넘긴다")
    void listParams() throws Exception {
        when(commentService.list(7L, 11L, 31L, 20))
                .thenReturn(new CommentPageResponseDTO(List.of(sample()), 32L));

        mockMvc.perform(get(BASE + "/posts/11/comments").with(customerAuth()).param("cursor", "31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value(32));
    }

    @Test
    @DisplayName("삭제는 로그인한 고객 ID와 경로의 댓글 번호로 서비스를 부르고, 서비스 오류는 상태 코드로 바뀐다")
    void deleteAndErrors() throws Exception {
        mockMvc.perform(delete(BASE + "/comments/31").with(customerAuth()).with(csrf()))
                .andExpect(status().isOk());
        verify(commentService).delete(7L, 31L);

        org.mockito.Mockito.doThrow(new AppException(ErrorType.COMMUNITY_NOT_AUTHOR))
                .when(commentService)
                .delete(7L, 32L);
        when(commentService.write(eq(7L), eq(11L), any()))
                .thenThrow(new AppException(ErrorType.COMMUNITY_REPLY_TARGET_INVALID));

        mockMvc.perform(delete(BASE + "/comments/32").with(customerAuth()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_AUTHOR"));
        mockMvc.perform(
                        post(BASE + "/posts/11/comments")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\",\"replyToCommentId\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_REPLY_TARGET_INVALID"));
    }
}
