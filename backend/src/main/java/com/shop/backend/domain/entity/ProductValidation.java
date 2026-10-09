package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Business-rule validation for new product registration. */
public final class ProductValidation {

    /** Must match the category list shown in the storefront sidebar and admin form. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "뷰티", "신발", "상의", "아우터", "바지", "원피스/스커트",
            "가방", "모자", "소품", "속옷/홈웨어", "스포츠/레저"
    );

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_BEST_SIZE = 20;

    private ProductValidation() {
    }

    // Stored space-joined in products.hashtags VARCHAR(100) (V9): see ProductValidationTest for the size invariant.
    static final int MAX_HASHTAGS = 3;
    static final int MAX_HASHTAG_LENGTH = 20;
    /** Work cap: more raw entries than this is rejected before any per-tag processing. */
    private static final int MAX_RAW_HASHTAGS = 20;
    /** Same pattern as the frontend: letters, combining marks (Thai, Devanagari, ...), numbers, underscore. */
    private static final Pattern HASHTAG_CHARS = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]+");

    public static void validateNewProduct(String name, String description, Integer price, String imageUrl, Integer stock,
                                          String category, Integer originalPrice) {
        if (isBlank(name) || isBlank(description) || isBlank(imageUrl)
                || name.length() > 200 || description.length() > 2000 || imageUrl.length() > 2000) {
            throw new ValidationException("이름, 설명, 이미지 URL을 올바르게 입력해주세요.");
        }
        if (price == null || price < 0) {
            throw new ValidationException("가격이 올바르지 않습니다.");
        }
        if (stock == null || stock < 0) {
            throw new ValidationException("재고 수량이 올바르지 않습니다.");
        }
        if (category != null && !category.isEmpty() && !ALLOWED_CATEGORIES.contains(category)) {
            throw new ValidationException("올바르지 않은 카테고리입니다.");
        }
        // price >= 0 is checked above, so this also rules out zero/negative original prices
        if (originalPrice != null && originalPrice <= price) {
            throw new ValidationException("정가는 판매가보다 커야 해요.");
        }
    }

    /**
     * Cleans and validates client hashtags. Per tag, in this order: Unicode NFC (so decomposed Hangul from
     * macOS counts as syllables and NFC/NFD spellings are one tag), trim, strip leading '#' / full-width '＃',
     * drop empties, de-duplicate case-insensitively (first spelling wins; the key uses
     * {@code toLowerCase(Locale.ROOT)}, so 'ß' is not 'SS' and 'İ' is not 'i'). A tag is 1..20 code points of
     * letters, combining marks, numbers or underscore: inner whitespace (e.g. "# foo" after stripping), zero-width
     * characters and NBSP are rejected, not repaired. At most 3 tags; a raw list over 20 entries is rejected
     * before any processing.
     */
    public static List<String> normalizeHashtags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > MAX_RAW_HASHTAGS) {
            throw new ValidationException("해시태그는 최대 " + MAX_HASHTAGS + "개까지 입력할 수 있어요.");
        }
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (String tag : raw) {
            if (tag == null) {
                continue;
            }
            String t = Normalizer.normalize(tag, Normalizer.Form.NFC).strip();
            int start = 0;
            while (start < t.length() && (t.charAt(start) == '#' || t.charAt(start) == '＃')) {
                start++;
            }
            t = t.substring(start);
            if (!t.isEmpty()) {
                byLowerCase.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
            }
        }
        if (byLowerCase.size() > MAX_HASHTAGS) {
            throw new ValidationException("해시태그는 최대 " + MAX_HASHTAGS + "개까지 입력할 수 있어요.");
        }
        for (String t : byLowerCase.values()) {
            if (t.codePointCount(0, t.length()) > MAX_HASHTAG_LENGTH) {
                throw new ValidationException("해시태그는 하나당 " + MAX_HASHTAG_LENGTH + "자까지 입력할 수 있어요.");
            }
            if (!HASHTAG_CHARS.matcher(t).matches()) {
                throw new ValidationException("해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요.");
            }
        }
        return List.copyOf(byLowerCase.values());
    }

    public static void validatePage(int page, int size) {
        if (page < 0) {
            throw new ValidationException("페이지 번호가 올바르지 않아요.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("상품 수는 1~" + MAX_PAGE_SIZE + "개 사이여야 해요.");
        }
        // the row offset (page * size) must fit an int, otherwise the query layer overflows -> 500
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ValidationException("페이지 번호가 너무 커요.");
        }
    }

    public static void validateBestSize(int size) {
        if (size < 1 || size > MAX_BEST_SIZE) {
            throw new ValidationException("상품 수는 1~" + MAX_BEST_SIZE + "개 사이여야 해요.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
