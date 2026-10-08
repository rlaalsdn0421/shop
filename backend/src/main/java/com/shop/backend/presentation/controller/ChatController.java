package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ChatAnswerer;
import com.shop.backend.domain.entity.ChatValidation;
import com.shop.backend.presentation.dto.ChatDtos;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatAnswerer chatAnswerer;

    public ChatController(ChatAnswerer chatAnswerer) {
        this.chatAnswerer = chatAnswerer;
    }

    @PostMapping
    public ChatDtos.ChatResponse chat(@RequestBody ChatDtos.ChatRequest request) {
        // Validate here so every ChatAnswerer implementation (including a future LLM one) gets a checked message.
        String message = ChatValidation.validateMessage(request.message());
        return ChatDtos.ChatResponse.from(chatAnswerer.answer(message));
    }
}
