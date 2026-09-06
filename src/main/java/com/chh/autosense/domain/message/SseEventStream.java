package com.chh.autosense.domain.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SseEmitter 封装(R18/FR-021):事件写入与生命周期。
 * 进入等待用户态(awaiting)或终态(conclusion)后 complete 关流;
 * 业务异常发 error 事件后关流;断线不补发。
 */
public class SseEventStream {

    private static final Logger log = LoggerFactory.getLogger(SseEventStream.class);
    /** 修复执行最长 10 分钟(R18 流内推进) */
    public static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SseEventStream() {
        this.emitter = new SseEmitter(TIMEOUT_MS);
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void send(SseEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
        } catch (IOException | IllegalStateException e) {
            // 客户端断线:不补发,仅记录(FR-021)
            log.debug("SSE 发送失败(客户端可能已断开): {}", e.getMessage());
            closed.set(true);
        }
    }

    /** 等待用户态:发 awaiting 后关流。 */
    public void awaitUser(long sessionId, String prompt) {
        send(SseEvent.awaiting(sessionId, prompt));
        complete();
    }

    /** 终态:发 conclusion 后关流。 */
    public void conclude(Object conclusion) {
        send(SseEvent.conclusion(conclusion));
        complete();
    }

    /** 业务错误:发 error 事件后正常关流(错误信号由事件承载,FR-021)。 */
    public void error(String code, String message, Long sessionId) {
        send(SseEvent.error(code, message, sessionId));
        complete();
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }
}
