package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.FaqChatAnswerer;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses the real (DB-free) FAQ answerer to prove the HTTP-level message validation. */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtService.class, FaqChatAnswerer.class})
class ChatControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions send(String json) throws Exception {
        return mockMvc.perform(post("/api/chat").contentType("application/json").content(json));
    }

    @Test
    void 성공_200자_메시지는_허용된다() throws Exception {
        send("{\"message\":\"" + "가".repeat(200) + "\"}").andExpect(status().isOk());
    }

    @Test
    void 성공_도움말은_추천질문_5개를_반환한다() throws Exception {
        send("{\"message\":\"도움말\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions.length()").value(5));
    }

    @Test
    void 실패_공백_메시지는_400을_반환한다() throws Exception {
        send("{\"message\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 실패_메시지가_없으면_400을_반환한다() throws Exception {
        send("{}").andExpect(status().isBadRequest());
    }

    @Test
    void 실패_전각공백만_있는_메시지는_400을_반환한다() throws Exception {
        send("{\"message\":\"\\u3000\\u3000\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 실패_NBSP만_있는_메시지는_400을_반환한다() throws Exception {
        send("{\"message\":\"\\u00a0\\u00a0\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 실패_본문이_비어_있으면_500이_아니라_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/chat").contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문을 읽을 수 없습니다."));
    }

    @Test
    void 실패_JSON이_아닌_본문은_400을_반환한다() throws Exception {
        send("this is not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문을 읽을 수 없습니다."));
    }

    @Test
    void 실패_GET_요청은_405를_반환한다() throws Exception {
        mockMvc.perform(get("/api/chat"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 실패_JSON이_아닌_콘텐츠_타입은_415를_반환한다() throws Exception {
        mockMvc.perform(post("/api/chat").contentType("text/plain").content("도움말"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 실패_200자를_넘으면_400을_반환한다() throws Exception {
        send("{\"message\":\"" + "가".repeat(201) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
