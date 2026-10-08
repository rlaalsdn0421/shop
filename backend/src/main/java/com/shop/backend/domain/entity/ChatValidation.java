package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

/** Business-rule validation for chatbot messages. */
public final class ChatValidation {

    public static final int MAX_MESSAGE_LENGTH = 200;

    private ChatValidation() {
    }

    /** Returns the stripped message, or throws if it is blank or too long. */
    public static String validateMessage(String message) {
        // strip() handles U+3000 etc.; isSpaceChar also catches NBSP, which isWhitespace does not.
        String stripped = message == null ? "" : message.strip();
        boolean blank = stripped.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
        if (blank || stripped.length() > MAX_MESSAGE_LENGTH) {
            throw new ValidationException("메시지는 1~" + MAX_MESSAGE_LENGTH + "자로 입력해주세요.");
        }
        return stripped;
    }
}
