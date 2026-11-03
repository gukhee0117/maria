package com.app.maria.domain.community.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.LikeResponseDTO;
import com.app.maria.domain.community.dto.response.MySummaryResponseDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.service.CommunityMyActivityService;
import com.app.maria.domain.community.service.CommunityReactionService;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({CommunityReactionApi.class, CommunityMyActivityApi.class})
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CommunityReactionAndMyApiTest {
    private static final String BASE = "/api/customer/community";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityReactionService reactionService;
    @MockitoBean private CommunityMyActivityService myActivityService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, CSRF 없이 좋아요를 바꾸면 403이다")
    void authAndCsrf() throws Exception {
        mockMvc.perform(put(BASE + "/posts/11/like").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/me/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(BASE + "/posts/11/like").with(customerAuth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/posts/11/like").with(customerAuth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/comments/31/like").with(customerAuth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/comments/31/like").with(customerAuth()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reactionService, myActivityService);
    }

    @Test
    @DisplayName("좋아요 켜기·끄기는 로그인한 고객 ID와 경로 번호로 서비스를 부른다")
    void likes() throws Exception {
        when(reactionService.likePost(7L, 11L)).thenReturn(new LikeResponseDTO(true, 3));
        when(reactionService.unlikePost(7L, 11L)).thenReturn(new LikeResponseDTO(false, 2));
        when(reactionService.likeComment(7L, 31L)).thenReturn(new LikeResponseDTO(true, 1));
        when(reactionService.unlikeComment(7L, 31L)).thenReturn(new LikeResponseDTO(false, 0));

        mockMvc.perform(put(BASE + "/posts/11/like").with(customerAuth()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3));
        mockMvc.perform(delete(BASE + "/posts/11/like").with(customerAuth()).with(csrf()))
                .andExpect(jsonPath("$.data.liked").value(false));
        mockMvc.perform(put(BASE + "/comments/31/like").with(customerAuth()).with(csrf()))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        mockMvc.perform(delete(BASE + "/comments/31/like").with(customerAuth()).with(csrf()))
                .andExpect(jsonPath("$.data.likeCount").value(0));

        verify(reactionService).likePost(7L, 11L);
        verify(reactionService).unlikeComment(7L, 31L);
    }

    @Test
    @DisplayName("번호가 0 이하이면 400이고, 서비스 오류는 상태 코드로 바뀐다")
    void validationAndErrors() throws Exception {
        mockMvc.perform(put(BASE + "/posts/0/like").with(customerAuth()).with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/comments/-1/like").with(customerAuth()).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reactionService);

        doThrow(new AppException(ErrorType.COMMUNITY_POST_NOT_VISIBLE))
                .when(reactionService)
                .likePost(7L, 12L);
        mockMvc.perform(put(BASE + "/posts/12/like").with(customerAuth()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_VISIBLE"));
    }

    @Test
    @DisplayName("내가 쓴 글·댓글·프로필 카드는 로그인한 고객 기준이고 ticker·cursor를 그대로 넘긴다")
    void myActivity() throws Exception {
        when(myActivityService.myPosts(7L, "000660", 50L, 20))
                .thenReturn(new PostPageResponseDTO(List.of(), 9L));
        when(myActivityService.myComments(eq(7L), isNull(), isNull(), anyInt()))
                .thenReturn(new CommentPageResponseDTO(List.of(), null));
        when(myActivityService.summary(7L))
                .thenReturn(new MySummaryResponseDTO("반도체개미", 9, 7, false));

        mockMvc.perform(
                        get(BASE + "/me/posts")
                                .with(customerAuth())
                                .param("ticker", "000660")
                                .param("cursor", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value(9));
        mockMvc.perform(get(BASE + "/me/comments").with(customerAuth())).andExpect(status().isOk());
        String body =
                mockMvc.perform(get(BASE + "/me/summary").with(customerAuth()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.nickname").value("반도체개미"))
                        .andExpect(jsonPath("$.data.postCount").value(9))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("customerId");
        verify(myActivityService).myPosts(7L, "000660", 50L, 20);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/me/posts?size=0",
                "/me/posts?size=51",
                "/me/posts?cursor=0",
                "/me/comments?size=-1",
                "/me/comments?ticker=ABCDEFGHIJKLMNOPQRSTU"
            })
    @DisplayName("내 활동의 범위를 벗어난 쿼리 값은 400이다")
    void myActivityValidation(String path) throws Exception {
        mockMvc.perform(get(BASE + path).with(customerAuth())).andExpect(status().isBadRequest());
        verifyNoInteractions(myActivityService);
    }
}
