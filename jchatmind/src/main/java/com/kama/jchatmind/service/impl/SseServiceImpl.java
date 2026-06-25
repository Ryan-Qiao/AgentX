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

@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 如果已存在旧连接，先主动 complete 它（避免泄露），
        // 同时注意：旧连接的 onCompletion 会后发后到，但我们用 remove(key,value) 避免误删。
        SseEmitter old = clients.put(chatSessionId, emitter);
        if (old != null) {
            try {
                old.complete();
            } catch (Exception ignored) {
                // 旧连接可能已经关闭，忽略
            }
        }

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
        emitter.onCompletion(() -> clients.remove(chatSessionId, emitter));
        emitter.onTimeout(() -> clients.remove(chatSessionId, emitter));
        emitter.onError((error) -> clients.remove(chatSessionId, emitter));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter == null) {
            // 连接不存在不应中断 Agent 流程，记录日志后返回
            log.warn("No SSE client for {}, drop message type={}",
                    chatSessionId, message.getType());
            return;
        }

        try {
            String sseMessageStr = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(sseMessageStr)
            );
        } catch (IOException e) {
            // 发送失败说明连接已断，清理 emitter，但不招异常
            log.warn("Failed to send SSE message to {}, removing emitter", chatSessionId, e);
            clients.remove(chatSessionId, emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }
}
