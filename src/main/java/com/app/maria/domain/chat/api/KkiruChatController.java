package com.app.maria.domain.chat.api;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import com.app.maria.domain.chat.service.KkiruChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@Slf4j
@RestController
@RequestMapping("/api/customer/chat")
@RequiredArgsConstructor
public class KkiruChatController {

    private final KkiruChatService kkiruChatService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequestDTO request) {
        SseEmitter emitter = new SseEmitter(180_000L);

        Disposable subscription =
                kkiruChatService.stream(request)
                        .subscribe(
                                token ->
                                        sendEvent(
                                                emitter, Map.of("type", "token", "content", token)),
                                error -> {
                                    log.error("끼루 챗봇 스트리밍 오류", error);
                                    sendEvent(
                                            emitter,
                                            Map.of(
                                                    "type",
                                                    "error",
                                                    "content",
                                                    "요청 처리 중 오류가 발생했어요."));
                                    emitter.complete();
                                },
                                () -> {
                                    sendEvent(emitter, Map.of("type", "done"));
                                    emitter.complete();
                                });

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(e -> subscription.dispose());

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, Map<String, String> payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .data(
                                    objectMapper.writeValueAsString(payload),
                                    MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
