package com.company.aitest.generation.session;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/projects/{projectId}/generation/sessions")
public class GenerationSseController {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping(value = "/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long projectId, @PathVariable Long sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout

        executor.submit(() -> {
            try {
                // Send initial connection event
                emitter.send(SseEmitter.event().name("connected").data("ok"));

                // Poll for new messages every 2 seconds for up to 5 minutes
                long deadline = System.currentTimeMillis() + 300_000;
                long lastMessageId = 0;

                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000);
                    // In a real implementation, check for new messages since lastMessageId
                    // For now, just keep the connection alive
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                }

                emitter.complete();
            } catch (IOException | InterruptedException e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> { /* ignore */ });

        return emitter;
    }
}
