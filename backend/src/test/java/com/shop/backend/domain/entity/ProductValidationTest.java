package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductValidationTest {

    private static final String ORIGINAL_PRICE_MSG = "정가는 판매가보다 커야 해요.";
    private static final String TOO_MANY_MSG = "해시태그는 최대 3개까지 입력할 수 있어요.";
    private static final String TOO_LONG_MSG = "해시태그는 하나당 20자까지 입력할 수 있어요.";
    private static final String CHARS_MSG = "해시태그는 글자, 숫자, 밑줄(_)만 쓸 수 있어요.";

    private static void validate(Integer price, Integer originalPrice) {
        ProductValidation.validateNewProduct("n", "d", price, "http://img", 1, null, originalPrice);
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    // ---- originalPrice ----

    @Test
    void 성공_정가가_없으면_통과한다() {
        assertThatCode(() -> validate(1000, null)).doesNotThrowAnyException();
    }

    @Test
    void 성공_정가가_판매가보다_크면_통과한다() {
        assertThatCode(() -> validate(1000, 1001)).doesNotThrowAnyException();
        assertThatCode(() -> validate(0, 1)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 0, 999, 1000})
    void 실패_정가가_판매가_이하면_거부한다(int originalPrice) {
        assertThatThrownBy(() -> validate(1000, originalPrice))
                .isInstanceOf(ValidationException.class)
                .hasMessage(ORIGINAL_PRICE_MSG);
    }

    @Test
    void 실패_정가가_0이고_판매가도_0이면_거부한다() {
        assertThatThrownBy(() -> validate(0, 0)).isInstanceOf(ValidationException.class).hasMessage(ORIGINAL_PRICE_MSG);
    }

    // ---- hashtags: normalization ----

    @Test
    void 성공_해시태그가_없으면_빈_목록이다() {
        assertThat(ProductValidation.normalizeHashtags(null)).isEmpty();
        assertThat(ProductValidation.normalizeHashtags(List.of())).isEmpty();
    }

    @Test
    void 성공_앞뒤_공백과_앞쪽_샵을_하나_이상_떼어낸다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("#여름", "  ##sale  ", "###_x1")))
                .containsExactly("여름", "sale", "_x1");
    }

    @Test
    void 성공_빈_항목과_샵만_있는_항목은_버린다() {
        assertThat(ProductValidation.normalizeHashtags(Arrays.asList("", "  ", "#", "##", null, "ok")))
                .containsExactly("ok");
    }

    @Test
    void 성공_대소문자만_다른_중복은_처음_것만_남긴다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("Sale", "#sale", "SALE", "여름")))
                .containsExactly("Sale", "여름");
    }

    @Test
    void 성공_중복을_빼고_3개면_통과한다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("a", "A", "b", "c"))).containsExactly("a", "b", "c");
    }

    @Test
    void 성공_글자_숫자_밑줄은_허용한다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("abc_123", "한글", "日本語")))
                .containsExactly("abc_123", "한글", "日本語");
    }

    // ---- hashtags: count ----

    @Test
    void 성공_해시태그_3개는_통과한다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("a", "b", "c"))).hasSize(3);
    }

    @Test
    void 실패_해시태그_4개는_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of("a", "b", "c", "d")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_MANY_MSG);
    }

    // ---- hashtags: length (code points) ----

    @Test
    void 성공_한글_20자는_통과한다() {
        String tag = repeat("가", 20);
        assertThat(ProductValidation.normalizeHashtags(List.of(tag))).containsExactly(tag);
    }

    @Test
    void 실패_한글_21자는_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(repeat("가", 21))))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_LONG_MSG);
    }

    @Test
    void 성공_서로게이트_쌍_글자_20개는_코드포인트_기준으로_통과한다() {
        String tag = repeat("𠮷", 20); // U+20BB7, a letter outside the BMP: 40 UTF-16 chars, 20 code points
        assertThat(ProductValidation.normalizeHashtags(List.of(tag))).containsExactly(tag);
    }

    @Test
    void 실패_서로게이트_쌍_글자_21개는_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(repeat("𠮷", 21))))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_LONG_MSG);
    }

    @Test
    void 실패_이모지는_글자가_아니라서_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of("😀")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(CHARS_MSG);
    }

    // ---- hashtags: characters ----

    // ---- hashtags: Unicode (NFC, combining marks, full-width #, invisible characters) ----

    private static String nfd(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD);
    }

    @Test
    void 성공_NFD_한글은_음절로_세어_20자_제한에_걸리지_않는다() {
        String nfc = "가나다라마바사아자차카"; // 11 syllables = 22 code points when decomposed
        assertThat(nfd(nfc).codePointCount(0, nfd(nfc).length())).isGreaterThan(20);

        assertThat(ProductValidation.normalizeHashtags(List.of(nfd(nfc)))).containsExactly(nfc);
    }

    @Test
    void 성공_NFC와_NFD로_쓴_같은_한글_태그는_중복으로_본다() {
        String nfc = "가나다라마바사"; // 7 syllables
        assertThat(ProductValidation.normalizeHashtags(List.of(nfd(nfc), nfc))).containsExactly(nfc);
        assertThat(ProductValidation.normalizeHashtags(List.of(nfc, nfd(nfc)))).containsExactly(nfc);
    }

    @Test
    void 성공_NFD_라틴_café는_허용하고_NFC와_중복으로_본다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("café"))).containsExactly("café");
        assertThat(ProductValidation.normalizeHashtags(List.of("café", "café", "CAFÉ")))
                .containsExactly("café");
    }

    @Test
    void 성공_결합문자가_필요한_태국어와_데바나가리는_허용한다() {
        String thai = "สวัสดี"; // contains Mn marks U+0E31, U+0E35
        String devanagari = "नमस्ते"; // contains virama and vowel sign (Mn)
        assertThat(ProductValidation.normalizeHashtags(List.of(thai, devanagari))).containsExactly(thai, devanagari);
    }

    @Test
    void 성공_전각_샵도_앞쪽_표시로_떼어낸다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("＃여름", "＃＃Sale", "#＃_x")))
                .containsExactly("여름", "Sale", "_x");
        assertThat(ProductValidation.normalizeHashtags(List.of("＃#y", "＃", "＃＃"))).containsExactly("y");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a＃b", "a#b", "a＃", "a＃＃"})
    void 실패_안쪽이나_뒤쪽의_샵은_전각이어도_거부한다(String tag) {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(tag)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(CHARS_MSG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a​b", "​ab", "ab​", "a‍b", "‍ab", "a b", " ab", "ab "})
    void 실패_제로폭_문자와_NBSP는_거부한다(String tag) {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(tag)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(CHARS_MSG);
    }

    @Test
    void 성공_숫자만_또는_밑줄만_있는_태그도_허용한다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("123", "___"))).containsExactly("123", "___");
    }

    @Test
    void 성공_중복_판정은_Locale_ROOT_소문자_기준이라_ß와_SS는_다른_태그다() {
        assertThat(ProductValidation.normalizeHashtags(List.of("ß", "SS"))).containsExactly("ß", "SS");
        assertThat(ProductValidation.normalizeHashtags(List.of("STRASSE", "straße"))).hasSize(2);
    }

    @Test
    void 성공_터키어_İ는_i와_다른_태그지만_i_점_조합과는_같은_태그다() {
        // toLowerCase(Locale.ROOT) of U+0130 is "i" + U+0307, not plain "i"
        assertThat(ProductValidation.normalizeHashtags(List.of("İ", "i"))).containsExactly("İ", "i");
        assertThat(ProductValidation.normalizeHashtags(List.of("İ", "i̇"))).containsExactly("İ");
    }

    @Test
    void 성공_한글_라틴_서로게이트_글자가_섞인_정확히_20자는_통과한다() {
        String tag = repeat("가", 7) + repeat("a", 7) + repeat("𠮷", 6); // 20 code points, 33 UTF-16 chars
        assertThat(ProductValidation.normalizeHashtags(List.of(tag))).containsExactly(tag);
    }

    @Test
    void 실패_한글_라틴_서로게이트_글자가_섞인_21자는_거부한다() {
        String tag = repeat("가", 7) + repeat("a", 7) + repeat("𠮷", 7);
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(tag)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_LONG_MSG);
    }

    // ---- hashtags: work cap and storage ----

    @Test
    void 성공_원본_목록이_20개면_처리하고_중복을_합친다() {
        assertThat(ProductValidation.normalizeHashtags(Collections.nCopies(20, "a"))).containsExactly("a");
    }

    @Test
    void 실패_원본_목록이_21개면_내용과_상관없이_바로_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(Collections.nCopies(21, "a")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_MANY_MSG);
    }

    @Test
    void 실패_아주_큰_목록도_바로_거부한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(Collections.nCopies(1_000_000, "a")))
                .isInstanceOf(ValidationException.class)
                .hasMessage(TOO_MANY_MSG);
    }

    @Test
    void 성공_해시태그_최대치를_공백으로_이어도_VARCHAR_100_컬럼에_들어간다() {
        // products.hashtags is VARCHAR(100) in V9; raising the limits must come with a new migration
        int joinedMax = ProductValidation.MAX_HASHTAGS * ProductValidation.MAX_HASHTAG_LENGTH
                + (ProductValidation.MAX_HASHTAGS - 1);
        assertThat(joinedMax).isLessThanOrEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a\tb", "# foo", "a-b", "a!", "a,b", "a#b", "a.b", "<b>"})
    void 실패_공백이나_허용되지_않는_문자가_있으면_거부한다(String tag) {
        assertThatThrownBy(() -> ProductValidation.normalizeHashtags(List.of(tag)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(CHARS_MSG);
    }
}
