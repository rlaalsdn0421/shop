package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ChatAnswer;
import com.shop.backend.application.service.ChatAnswerer;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtService.class})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatAnswerer chatAnswerer;

    @Test
    void 성공_인증없이_답변과_추천질문을_반환한다() throws Exception {
        when(chatAnswerer.answer("배송")).thenReturn(new ChatAnswer("배송 답변", List.of("질문1", "질문2")));

        mockMvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"배송\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("배송 답변"))
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0]").value("질문1"));
    }

    @Test
    void 성공_앞뒤_공백을_제거한_메시지가_답변기에_전달된다() throws Exception {
        when(chatAnswerer.answer("배송")).thenReturn(new ChatAnswer("배송 답변", List.of()));

        mockMvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"  배송  \"}"))
                .andExpect(status().isOk());

        verify(chatAnswerer).answer("배송");
    }

    @Test
    void 실패_201자_메시지는_400이고_답변기에_전달되지_않는다() throws Exception {
        mockMvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"" + "가".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatAnswerer);
    }

    @Test
    void 실패_공백_메시지는_400이고_답변기에_전달되지_않는다() throws Exception {
        mockMvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatAnswerer);
    }

    @Test
    void 실패_검증_예외는_400과_에러_본문으로_매핑된다() throws Exception {
        when(chatAnswerer.answer("x")).thenThrow(new ValidationException("메시지 오류"));

        mockMvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("메시지 오류"));
    }
}
