package com.app.maria.domain.community.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.dto.request.PostUpdateRequestDTO;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.dto.response.TickerRankResponseDTO;
import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import com.app.maria.domain.community.service.CommunityPostService;
import com.app.maria.domain.community.service.CommunityTickerService;
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

@WebMvcTest(CommunityPostApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CommunityPostApiTest {
    private static final String BASE = "/api/customer/community";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityPostService postService;
    @MockitoBean private CommunityTickerService tickerService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static PostResponseDTO sample() {
        return new PostResponseDTO(
                11L,
                "000660",
                "반도체개미",
                null,
                true,
                "본문",
                CommunityStatus.VISIBLE,
                0,
                0,
                false,
                null,
                false,
                true,
                AT);
    }

    @Test
    @DisplayName("로그인하지 않으면 모든 API가 401이다")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(BASE + "/tickers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/tickers/ranking")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/posts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/posts/popular")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/posts/11")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(postService, tickerService);
    }

    @Test
    @DisplayName("CSRF 토큰 없이 쓰기·수정·삭제하면 403이고 서비스를 부르지 않는다")
    void changesNeedCsrf() throws Exception {
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(
                        put(BASE + "/posts/11")
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/posts/11").with(customerAuth()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(postService);
    }

    @Test
    @DisplayName("글을 쓰면 로그인한 고객 ID로 서비스를 부르고, 본문의 customerId·status 같은 값은 무시한다")
    void writeUsesAuthenticatedCustomer() throws Exception {
        when(postService.write(eq(7L), any())).thenReturn(sample());

        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"customerId\":999,\"status\":\"HIDDEN\",\"ticker\":\"000660\",\"content\":\"본문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.authorNickname").value("반도체개미"));

        ArgumentCaptor<PostWriteRequestDTO> captor =
                ArgumentCaptor.forClass(PostWriteRequestDTO.class);
        verify(postService).write(eq(7L), captor.capture());
        assertThat(captor.getValue().ticker()).isEqualTo("000660");
        assertThat(captor.getValue().content()).isEqualTo("본문");
    }

    @Test
    @DisplayName("응답 JSON 어디에도 customerId·작성자 ID가 없다")
    void responseHasNoCustomerId() throws Exception {
        when(postService.list(eq(7L), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new PostPageResponseDTO(List.of(sample()), null));

        String body =
                mockMvc.perform(get(BASE + "/posts").with(customerAuth()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContainIgnoringCase("customerId")
                .doesNotContainIgnoringCase("authorId");
        assertThat(body).contains("authorNickname");
    }

    @Test
    @DisplayName("본문이 없거나 2000자를 넘으면 400이다")
    void writeValidation() throws Exception {
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"" + "가".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{깨진 json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(postService);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/posts?size=0",
                "/posts?size=51",
                "/posts?size=-1",
                "/posts?cursor=0",
                "/posts?cursor=-5",
                "/posts?ticker=ABCDEFGHIJKLMNOPQRSTU",
                "/posts/popular?size=0",
                "/posts/popular?size=31",
                "/posts/0",
                "/posts/abc",
                "/posts?cursor=abc",
                "/posts?size=abc",
                "/posts/-3",
                "/tickers/ranking?size=0",
                "/tickers/ranking?size=31"
            })
    @DisplayName("범위를 벗어난 쿼리·경로 값은 400이고 서비스를 부르지 않는다")
    void parameterValidation(String path) throws Exception {
        mockMvc.perform(get(BASE + path).with(customerAuth())).andExpect(status().isBadRequest());
        verifyNoInteractions(postService, tickerService);
    }

    @Test
    @DisplayName("목록은 기본 20개, 인기글은 기본 10개이고 ticker·q·cursor를 그대로 넘긴다")
    void listDefaultsAndParams() throws Exception {
        when(postService.list(eq(7L), eq("000660"), eq("hbm"), eq(55L), eq(20)))
                .thenReturn(new PostPageResponseDTO(List.of(sample()), 9L));
        when(postService.popular(eq(7L), isNull(), eq(10)))
                .thenReturn(new PostPageResponseDTO(List.of(), null));

        mockMvc.perform(
                        get(BASE + "/posts")
                                .with(customerAuth())
                                .param("ticker", "000660")
                                .param("q", "hbm")
                                .param("cursor", "55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value(9))
                .andExpect(jsonPath("$.data.items[0].id").value(11));
        mockMvc.perform(get(BASE + "/posts/popular").with(customerAuth()))
                .andExpect(status().isOk());

        verify(postService).list(7L, "000660", "hbm", 55L, 20);
        verify(postService).popular(7L, null, 10);
    }

    @Test
    @DisplayName("'popular'는 글 번호가 아니라 인기글 경로로 간다")
    void popularIsNotAPostId() throws Exception {
        when(postService.popular(eq(7L), isNull(), anyInt()))
                .thenReturn(new PostPageResponseDTO(List.of(), null));

        mockMvc.perform(get(BASE + "/posts/popular").with(customerAuth()))
                .andExpect(status().isOk());

        verify(postService, never()).detail(anyLong(), anyLong());
    }

    @Test
    @DisplayName("수정·삭제는 로그인한 고객 ID와 경로의 글 번호로 서비스를 부른다")
    void editAndDelete() throws Exception {
        when(postService.edit(eq(7L), eq(11L), any())).thenReturn(sample());

        mockMvc.perform(
                        put(BASE + "/posts/11")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"고침\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(BASE + "/posts/11").with(customerAuth()).with(csrf()))
                .andExpect(status().isOk());

        ArgumentCaptor<PostUpdateRequestDTO> captor =
                ArgumentCaptor.forClass(PostUpdateRequestDTO.class);
        verify(postService).edit(eq(7L), eq(11L), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("고침");
        verify(postService).delete(7L, 11L);
    }

    @Test
    @DisplayName("서비스 오류는 상태 코드와 code로 변환된다")
    void errorMapping() throws Exception {
        when(postService.edit(eq(7L), eq(11L), any()))
                .thenThrow(new AppException(ErrorType.COMMUNITY_NOT_AUTHOR));
        when(postService.detail(7L, 12L))
                .thenThrow(new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND));
        when(postService.write(eq(7L), any()))
                .thenThrow(new AppException(ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED));

        mockMvc.perform(
                        put(BASE + "/posts/11")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_AUTHOR"));
        mockMvc.perform(get(BASE + "/posts/12").with(customerAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
        mockMvc.perform(
                        post(BASE + "/posts")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"content\":\"@끼루 x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMUNITY_KKIRU_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("종목 드롭박스와 순위를 돌려준다")
    void tickers() throws Exception {
        when(tickerService.list())
                .thenReturn(List.of(new TickerResponseDTO("005930", "삼성전자", "DOMESTIC")));
        when(tickerService.ranking(12))
                .thenReturn(
                        List.of(
                                new TickerRankResponseDTO(
                                        1, "005930", "삼성전자", 7, 3, "배당 이야기가 많아요")));

        mockMvc.perform(get(BASE + "/tickers").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ticker").value("005930"))
                .andExpect(jsonPath("$.data[0].market").value("DOMESTIC"));
        mockMvc.perform(get(BASE + "/tickers/ranking").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].postCount").value(7))
                .andExpect(jsonPath("$.data[0].commentCount").value(3))
                .andExpect(jsonPath("$.data[0].reason").value("배당 이야기가 많아요"));
    }
}
