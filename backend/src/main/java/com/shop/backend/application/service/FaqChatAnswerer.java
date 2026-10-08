package com.shop.backend.application.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keyword-matching FAQ bot (no LLM, no DB). All answers below are DEMO/sample policy text,
 * not real shop policy. Highest keyword count wins; ties go to the earlier entry. The greeting
 * only answers when no other entry matches. The caller is responsible for validating the message.
 */
@Service
public class FaqChatAnswerer implements ChatAnswerer {

    private static final int MAX_SUGGESTIONS = 5;
    // Must be declared before FAQS: keywords are normalized while FAQS is being built.
    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private record Faq(String title, List<String> keywords, String answer) {
        Faq {
            keywords = keywords.stream().map(FaqChatAnswerer::normalize).toList();
        }

        int score(String normalizedInput) {
            return (int) keywords.stream().filter(normalizedInput::contains).count();
        }
    }

    // Order matters: it is the tie-break order and the suggestion order. Index 0 is the greeting.
    // The lock entry sits before signup/login so "로그인이 잠겼어요" (one hit each) goes to the lock entry.
    private static final List<Faq> FAQS = List.of(
            new Faq("도움말", List.of("도움말", "안녕", "하이", "hello", "시작"),
                    "안녕하세요! 무엇이 궁금하신가요? 아래 자주 묻는 질문을 눌러 보세요."),
            new Faq("배송은 얼마나 걸려요?", List.of("배송", "택배", "도착", "언제와"),
                    "주문 후 보통 2~3일 안에 배송돼요. 배송이 시작되면 주문 내역에서 확인할 수 있어요."),
            new Faq("교환·환불은 어떻게 하나요?", List.of("교환", "환불", "반품", "취소"),
                    "수령 후 7일 이내에 교환·환불을 신청할 수 있어요. 단순 변심은 왕복 배송비가 부과될 수 있어요."),
            new Faq("결제 수단은 무엇이 있나요?", List.of("결제", "카드", "무통장", "페이"),
                    "신용카드, 무통장입금, 간편결제를 지원해요. 무통장입금은 주문 후 24시간 안에 입금해 주세요."),
            new Faq("로그인을 5회 실패하면 어떻게 되나요?", List.of("잠금", "잠겼", "5회", "실패", "429"),
                    "같은 IP에서 5번 틀리면 5분간 막혀요. 5분이 지난 뒤에 다시 시도해 주세요."),
            new Faq("회원가입·로그인은 어떻게 하나요?", List.of("회원가입", "가입", "로그인", "비밀번호", "아이디"),
                    "상단의 회원가입 메뉴에서 이메일과 비밀번호로 가입할 수 있어요. 가입 후 로그인하면 주문할 수 있어요."),
            new Faq("재고가 없는 상품은 언제 입고되나요?", List.of("재고", "품절", "입고"),
                    "품절된 상품은 주문할 수 없어요. 입고 일정은 상품마다 달라서 따로 안내하지 않아요."));

    private static final Faq GREETING = FAQS.get(0);
    private static final String FALLBACK = "잘 이해하지 못했어요. 아래 자주 묻는 질문 중에서 골라 주세요.";

    @Override
    public ChatAnswer answer(String message) {
        String input = normalize(message);
        Faq best = null;
        int bestScore = 0;
        for (Faq faq : FAQS.subList(1, FAQS.size())) {
            int score = faq.score(input);
            if (score > bestScore) { // strict > keeps the first entry on ties
                best = faq;
                bestScore = score;
            }
        }
        if (best != null) {
            return new ChatAnswer(best.answer(), suggestionsExcluding(best));
        }
        if (GREETING.score(input) > 0) {
            return new ChatAnswer(GREETING.answer(), suggestionsExcluding(null));
        }
        return new ChatAnswer(FALLBACK, suggestionsExcluding(null));
    }

    private static List<String> suggestionsExcluding(Faq matched) {
        return FAQS.stream().skip(1).filter(f -> f != matched).map(Faq::title).limit(MAX_SUGGESTIONS).toList();
    }

    private static String normalize(String text) {
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return NON_LETTER_OR_DIGIT.matcher(composed).replaceAll("");
    }
}
