package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

    /** Restock is {@code stock + quantity} in an int column; a huge typed stock would overflow it on a later cancel/expiry. */
    public static final int MAX_STOCK = 1_000_000;
    private static final String MAX_STOCK_MESSAGE = "재고 수량은 1,000,000개 이하로 입력해 주세요.";

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

    public static final int MAX_IMAGES = 10;

    /**
     * Cleans and validates a gallery list (product_images, V17): 1..10 entries, each trimmed, non-blank, at most 2000
     * chars and starting with http:// or https://. The scheme rule is only for this new field; the legacy imageUrl
     * keeps its blank/length-only rule so existing clients do not break. Duplicate URLs are allowed.
     */
    public static List<String> normalizeImageUrls(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_IMAGES) {
            throw new ValidationException("이미지는 1~" + MAX_IMAGES + "장까지 등록할 수 있어요.");
        }
        return raw.stream().map(u -> {
            String t = u == null ? "" : u.trim();
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.isEmpty() || t.length() > 2000 || !(lower.startsWith("http://") || lower.startsWith("https://"))) {
                throw new ValidationException("이미지 URL은 http:// 또는 https://로 시작하는 2000자 이하 주소여야 해요.");
            }
            return t;
        }).toList();
    }

    public static final int MAX_OPTIONS = 50;
    static final int MAX_OPTION_NAME_LENGTH = 30;
    private static final Pattern COLOR_HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    /**
     * Cleans and validates the options of a new product (product_options, V18): 1..50 entries. Names are NFC
     * normalized and trimmed (blank = absent), at most 30 chars, and each option needs a color name or a size. A color
     * code (#RRGGBB) is only allowed together with a color name. Price is >= 0; the original price is absent or above
     * the price; stock is >= 0. No two options may share the same (color, size), compared case-insensitively. All
     * options of a product must be shaped alike (all have a color or none, all have a size or none) so the
     * storefront's color/size selectors make sense. The DB enforces the single-option rules too, but this runs first
     * so the client gets a Korean 400 instead of a constraint error.
     */
    public static List<NewProductOption> normalizeOptions(List<NewProductOption> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_OPTIONS) {
            throw new ValidationException("옵션은 1~" + MAX_OPTIONS + "개까지 등록할 수 있어요.");
        }
        List<NewProductOption> cleaned = new ArrayList<>();
        Set<List<String>> combos = new HashSet<>();
        for (NewProductOption option : raw) {
            if (option == null) {
                throw new ValidationException("옵션 정보가 올바르지 않아요.");
            }
            String color = cleanOptionText(option.colorName());
            String size = cleanOptionText(option.sizeLabel());
            String hex = option.colorHex() == null || option.colorHex().isBlank() ? null : option.colorHex().trim();
            if (color == null && size == null) {
                throw new ValidationException("옵션에는 색상 이름이나 사이즈가 하나는 있어야 해요.");
            }
            if (hex != null && (color == null || !COLOR_HEX.matcher(hex).matches())) {
                throw new ValidationException("색상 코드는 색상 이름과 함께 #RRGGBB 형식으로 입력해 주세요.");
            }
            Integer price = option.price();
            validateOptionPriceStock(price, option.originalPrice(), option.stock());
            List<String> combo = List.of(color == null ? "" : color.toLowerCase(Locale.ROOT),
                    size == null ? "" : size.toLowerCase(Locale.ROOT));
            if (!combos.add(combo)) {
                throw new ValidationException("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.");
            }
            cleaned.add(new NewProductOption(color, hex, size, price, option.originalPrice(), option.stock()));
        }
        boolean firstHasColor = cleaned.get(0).colorName() != null;
        boolean firstHasSize = cleaned.get(0).sizeLabel() != null;
        for (NewProductOption option : cleaned) {
            if ((option.colorName() != null) != firstHasColor || (option.sizeLabel() != null) != firstHasSize) {
                throw new ValidationException("모든 옵션이 색상과 사이즈를 같은 방식으로 입력해야 해요.");
            }
        }
        return List.copyOf(cleaned);
    }

    /** Price >= 0, original price absent or above the price, stock >= 0: the per-option number rules (register and edit). */
    public static void validateOptionPriceStock(Integer price, Integer originalPrice, Integer stock) {
        if (price == null || price < 0) {
            throw new ValidationException("옵션 가격이 올바르지 않습니다.");
        }
        if (originalPrice != null && originalPrice <= price) {
            throw new ValidationException("옵션 정가는 판매가보다 커야 해요.");
        }
        if (stock == null || stock < 0) {
            throw new ValidationException("옵션 재고 수량이 올바르지 않습니다.");
        }
        if (stock > MAX_STOCK) {
            throw new ValidationException(MAX_STOCK_MESSAGE);
        }
    }

    public static void validateExpectedStock(Integer expectedStock) {
        if (expectedStock == null || expectedStock < 0) {
            throw new ValidationException("기준 재고 값이 올바르지 않습니다.");
        }
    }

    /**
     * Checks the shape of a product edit against the options the product really has ({@code productOptionIds}, empty =
     * plain product) and the per-option numbers. A plain product takes no options; a product with options takes no
     * top-level price/originalPrice/stock/expectedStock and exactly one entry per existing option id (no unknown,
     * missing or duplicate ids). Returns the entries by id.
     */
    public static Map<String, OptionEdit> validateEditOptions(Collection<String> productOptionIds, Integer price,
                                                              Integer originalPrice, Integer stock, Integer expectedStock,
                                                              List<OptionEdit> options) {
        if (options != null && options.size() > MAX_OPTIONS) {
            throw new ValidationException("옵션은 1~" + MAX_OPTIONS + "개까지 등록할 수 있어요.");
        }
        boolean given = options != null && !options.isEmpty();
        if (productOptionIds.isEmpty()) {
            if (given) {
                throw new ValidationException("이 상품은 옵션이 없어서 옵션을 함께 보낼 수 없어요.");
            }
            validateExpectedStock(expectedStock);
            return Map.of();
        }
        if (price != null || originalPrice != null || stock != null || expectedStock != null) {
            throw new ValidationException("옵션이 있는 상품은 가격과 재고를 옵션별로 입력해 주세요.");
        }
        if (!given) {
            throw new ValidationException("옵션이 있는 상품은 모든 옵션의 정보를 보내야 해요.");
        }
        Map<String, OptionEdit> byId = new LinkedHashMap<>();
        for (OptionEdit option : options) {
            if (option == null || option.id() == null) {
                throw new ValidationException("옵션 정보가 올바르지 않아요.");
            }
            if (byId.put(option.id(), option) != null) {
                throw new ValidationException("같은 옵션이 중복되어 있어요.");
            }
            validateOptionPriceStock(option.price(), option.originalPrice(), option.stock());
            validateExpectedStock(option.expectedStock());
        }
        if (!byId.keySet().equals(Set.copyOf(productOptionIds))) {
            throw new ValidationException("옵션 목록이 상품의 옵션과 맞지 않아요. 새로고침한 뒤 다시 시도해 주세요.");
        }
        return byId;
    }

    /** Any control, format (zero-width, bidi), unassigned, private-use or separator character other than the plain space. */
    private static final Pattern OPTION_TEXT_FORBIDDEN = Pattern.compile("[\\p{C}\\p{Z}&&[^ ]]");
    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

    /**
     * One option text (color name or size): Unicode NFC, strip, blank = absent. Rejects control/format/separator
     * characters (NUL, tab, newline, zero-width, bidi overrides, NBSP-like spaces; only the plain space is allowed
     * inside), collapses runs of spaces to one (so "화이트  M" equals "화이트 M"), then enforces at most 30 chars and at
     * least one letter or digit. The limit counts UTF-16 units (String.length), so an emoji counts 2: deliberately
     * stricter than the DB's VARCHAR(30), which counts code points, and never looser.
     */
    private static String cleanOptionText(String value) {
        if (value == null) {
            return null;
        }
        String t = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (t.isEmpty()) {
            return null;
        }
        if (OPTION_TEXT_FORBIDDEN.matcher(t).find()) {
            throw new ValidationException("옵션 이름에 쓸 수 없는 문자가 있어요.");
        }
        t = t.replaceAll(" {2,}", " ");
        if (t.length() > MAX_OPTION_NAME_LENGTH) {
            throw new ValidationException("색상 이름과 사이즈는 " + MAX_OPTION_NAME_LENGTH + "자 이하로 입력해 주세요.");
        }
        if (!LETTER_OR_DIGIT.matcher(t).find()) {
            throw new ValidationException("옵션 이름에는 글자나 숫자가 하나는 있어야 해요.");
        }
        return t;
    }

    /** Name, description and representative image rules (pure input, no DB state): shared by register and edit. */
    public static void validateProductText(String name, String description, String imageUrl) {
        if (isBlank(name) || isBlank(description) || isBlank(imageUrl)
                || name.length() > 200 || description.length() > 2000 || imageUrl.length() > 2000) {
            throw new ValidationException("이름, 설명, 이미지 URL을 올바르게 입력해주세요.");
        }
    }

    /** {@code category} must already be trimmed; null/empty = no category. */
    public static void validateCategory(String category) {
        if (category != null && !category.isEmpty() && !ALLOWED_CATEGORIES.contains(category)) {
            throw new ValidationException("올바르지 않은 카테고리입니다.");
        }
    }

    public static void validateNewProduct(String name, String description, Integer price, String imageUrl, Integer stock,
                                          String category, Integer originalPrice) {
        validateProductText(name, description, imageUrl);
        if (price == null || price < 0) {
            throw new ValidationException("가격이 올바르지 않습니다.");
        }
        if (stock == null || stock < 0) {
            throw new ValidationException("재고 수량이 올바르지 않습니다.");
        }
        if (stock > MAX_STOCK) {
            throw new ValidationException(MAX_STOCK_MESSAGE);
        }
        validateCategory(category);
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
