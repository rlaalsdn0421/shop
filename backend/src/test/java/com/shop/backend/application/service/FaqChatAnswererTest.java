package com.shop.backend.application.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaqChatAnswererTest {

    private final FaqChatAnswerer answerer = new FaqChatAnswerer();

    @Test
    void 성공_키워드가_맞으면_해당_답변을_반환한다() {
        assertTrue(answerer.answer("택배 언제 오나요").answer().contains("2~3일"));
    }

    @Test
    void 성공_점수가_높은_항목이_이긴다() {
        // 교환(1) vs 결제+카드(2)
        assertTrue(answerer.answer("교환 결제 카드").answer().contains("신용카드"));
    }

    @Test
    void 성공_동점이면_먼저_선언된_항목이_이긴다() {
        // 배송(1) vs 환불(1) -> 배송이 먼저 선언됨
        assertTrue(answerer.answer("배송 환불").answer().contains("2~3일"));
        // 순서를 바꿔 입력해도 선언 순서가 기준이다
        assertTrue(answerer.answer("환불 배송").answer().contains("2~3일"));
    }

    @Test
    void 성공_일치하는_항목이_없으면_안내와_추천질문을_반환한다() {
        ChatAnswer result = answerer.answer("zzz qqq");
        assertTrue(result.answer().startsWith("잘 이해하지 못했어요"));
        assertEquals(5, result.suggestions().size());
    }

    @Test
    void 성공_대소문자_공백_특수문자를_무시한다() {
        ChatAnswer expected = answerer.answer("도움말");
        assertEquals(expected, answerer.answer("  도 움 말!!  "));
        assertEquals(expected, answerer.answer("HELLO"));
        assertEquals(answerer.answer("배송"), answerer.answer("  배.송?? "));
    }

    @Test
    void 성공_로그인_잠금_질문은_5분_잠금을_안내한다() {
        assertTrue(answerer.answer("로그인 5회 실패했어요").answer().contains("5분간 막혀요"));
        assertTrue(answerer.answer("429 에러가 나요").answer().contains("5분간 막혀요"));
    }

    @Test
    void 성공_도움말은_추천질문_5개를_반환한다() {
        assertEquals(5, answerer.answer("도움말").suggestions().size());
    }

    @Test
    void 성공_일반_답변의_추천질문은_자기_자신을_제외한_5개다() {
        for (String title : answerer.answer("도움말").suggestions()) {
            ChatAnswer result = answerer.answer(title);
            assertEquals(5, result.suggestions().size());
            assertFalse(result.suggestions().contains(title));
        }
    }

    @Test
    void 성공_모든_추천질문은_의도한_항목으로_되돌아온다() {
        Map<String, String> expectedSnippet = new LinkedHashMap<>();
        expectedSnippet.put("배송은 얼마나 걸려요?", "2~3일");
        expectedSnippet.put("교환·환불은 어떻게 하나요?", "7일 이내");
        expectedSnippet.put("결제 수단은 무엇이 있나요?", "신용카드");
        expectedSnippet.put("회원가입·로그인은 어떻게 하나요?", "회원가입 메뉴");
        expectedSnippet.put("로그인을 5회 실패하면 어떻게 되나요?", "5분간 막혀요");
        expectedSnippet.put("재고가 없는 상품은 언제 입고되나요?", "품절된 상품");

        // 도움말(5개 제한)에는 재고 질문이 없지만 다른 답변의 추천질문에는 나온다.
        List<String> fromGreeting = answerer.answer("도움말").suggestions();
        List<String> fromShipping = answerer.answer("배송은 얼마나 걸려요?").suggestions();
        assertTrue(fromShipping.contains("재고가 없는 상품은 언제 입고되나요?"));
        for (String s : fromGreeting) assertTrue(expectedSnippet.containsKey(s), s);
        for (String s : fromShipping) assertTrue(expectedSnippet.containsKey(s), s);

        expectedSnippet.forEach((title, snippet) ->
                assertTrue(answerer.answer(title).answer().contains(snippet), title));
    }

    private static final String GREETING_ANSWER = "안녕하세요! 무엇이 궁금하신가요? 아래 자주 묻는 질문을 눌러 보세요.";

    @Test
    void 성공_인사말만_있으면_인사로_답한다() {
        for (String greeting : List.of("안녕", "도움말", "하이")) {
            assertEquals(GREETING_ANSWER, answerer.answer(greeting).answer(), greeting);
        }
    }

    @Test
    void 성공_인사말이_섞여도_실제_질문으로_라우팅된다() {
        assertTrue(answerer.answer("안녕하세요 환불하고 싶어요").answer().contains("7일 이내"));
        assertTrue(answerer.answer("배송 시작했나요?").answer().contains("2~3일"));
        assertTrue(answerer.answer("하이힐 재고 있나요").answer().contains("품절된 상품"));
    }

    @Test
    void 실패_인사말이_아닌_엉뚱한_입력은_인사로_답하지_않는다() {
        assertFalse(answerer.answer("zzz").answer().equals(GREETING_ANSWER));
    }

    @Test
    void 성공_로그인_잠금_표현은_잠금_항목으로_간다() {
        for (String m : List.of("로그인이 잠겼어요", "로그인 실패했어요", "로그인 계속 실패")) {
            assertTrue(answerer.answer(m).answer().contains("5분간 막혀요"), m);
        }
    }

    @Test
    void 성공_가입과_비밀번호_질문은_가입_항목에_남는다() {
        assertTrue(answerer.answer("회원가입은 어떻게 하나요?").answer().contains("회원가입 메뉴"));
        assertTrue(answerer.answer("비밀번호 잊어버렸어요").answer().contains("회원가입 메뉴"));
    }

    @Test
    void 성공_분해된_한글_NFD도_매칭된다() {
        String nfd = java.text.Normalizer.normalize("배송", java.text.Normalizer.Form.NFD);
        assertFalse("배송".equals(nfd));
        assertEquals(answerer.answer("배송"), answerer.answer(nfd));
        assertTrue(answerer.answer(nfd).answer().contains("2~3일"));
    }

    @Test
    void 성공_터키어_로케일에서도_대문자_영문이_매칭된다() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(GREETING_ANSWER, answerer.answer("HELLO").answer());
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
