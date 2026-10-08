package com.shop.backend.presentation.dto;

import com.shop.backend.application.service.ChatAnswer;

import java.util.List;

/** Response/request shapes for the FAQ chatbot endpoint. */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer, List<String> suggestions) {
        public static ChatResponse from(ChatAnswer chatAnswer) {
            return new ChatResponse(chatAnswer.answer(), chatAnswer.suggestions());
        }
    }
}
