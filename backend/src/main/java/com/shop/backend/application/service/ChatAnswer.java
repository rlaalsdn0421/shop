package com.shop.backend.application.service;

import java.util.List;

/** A chatbot reply plus follow-up questions the user can click to send back. */
public record ChatAnswer(String answer, List<String> suggestions) {
}
