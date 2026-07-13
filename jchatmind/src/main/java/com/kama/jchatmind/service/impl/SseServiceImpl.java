package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        clients.computeIfAbsent(chatSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            // init 发送失败不应该中断连接注册，仅记录日志
            log.warn("Failed to send init event for {}", chatSessionId, e);
        }

        // 关键：使用 remove(key, value) 原子操作，只有当当前映射仍然是本 emitter 时才删除。
        // 这样当 React StrictMode 双重 mount 或快速重连时，
        // 旧 emitter 的 cleanup 不会误删新 emitter。
        emitter.onCompletion(() -> remove(chatSessionId, emitter));
        emitter.onTimeout(() -> remove(chatSessionId, emitter));
        emitter.onError((error) -> remove(chatSessionId, emitter));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        Set<SseEmitter> emitters = clients.get(chatSessionId);

        if (emitters == null || emitters.isEmpty()) {
            // 连接不存在不应中断 Agent 流程，记录日志后返回
            log.warn("No SSE client for {}, drop message type={}",
                    chatSessionId, message.getType());
            return;
        }

        String sseMessageStr;
        try {
            sseMessageStr = objectMapper.writeValueAsString(message);
        } catch (IOException e) {
            log.warn("Failed to serialize SSE message", e);
            return;
        }
        for (SseEmitter emitter : Set.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name("message").data(sseMessageStr));
            } catch (IOException e) {
                log.warn("Failed to send SSE message to {}, removing emitter", chatSessionId, e);
                remove(chatSessionId, emitter);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void remove(String chatSessionId, SseEmitter emitter) {
        clients.computeIfPresent(chatSessionId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
