package com.shop.backend.application.service;

/** Seam so the keyword FAQ implementation can later be swapped for an LLM-backed one. */
public interface ChatAnswerer {

    ChatAnswer answer(String message);
}
