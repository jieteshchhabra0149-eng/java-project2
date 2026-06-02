package com.chatapp.controller;

import com.chatapp.model.AiChatRequest;
import com.chatapp.model.AiChatResponse;
import com.chatapp.service.AiGuideService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class AiGuideController {

    private final AiGuideService aiGuideService;

    public AiGuideController(AiGuideService aiGuideService) {
        this.aiGuideService = aiGuideService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(
        @RequestBody AiChatRequest request,
        HttpSession session
    ) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(AiChatResponse.fail("Message is required."));
        }
        AiChatResponse response = aiGuideService.chat(
            session.getId(),
            request.getMessage(),
            request.getHistory(),
            request.getTopic()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody AiChatRequest request, HttpSession session) {
        SseEmitter emitter = new SseEmitter(120_000L);
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            emitter.completeWithError(new IllegalArgumentException("Message is required."));
            return emitter;
        }
        aiGuideService.streamChat(
            session.getId(),
            request.getMessage(),
            request.getHistory(),
            request.getTopic(),
            emitter
        );
        return emitter;
    }
}
