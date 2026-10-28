package com.app.maria.domain.chat.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.chat.service.KkiruChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = KkiruChatController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
class KkiruChatControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private KkiruChatService kkiruChatService;

    @Test
    void question이_blank면_400_반환() throws Exception {
        mockMvc.perform(
                        post("/api/customer/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\":[], \"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void question이_없으면_400_반환() throws Exception {
        mockMvc.perform(
                        post("/api/customer/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messages가_null이면_400_반환() throws Exception {
        mockMvc.perform(
                        post("/api/customer/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"RIA가 뭔가요?\"}"))
                .andExpect(status().isBadRequest());
    }
}
