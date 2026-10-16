package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductOptionValidationTest {

    private static NewProductOption opt(String color, String hex, String size, Integer price, Integer original, Integer stock) {
        return new NewProductOption(color, hex, size, price, original, stock);
    }

    private static NewProductOption sizeOnly(String size) {
        return opt(null, null, size, 1000, null, 1);
    }

    private static void assertRejected(String message, NewProductOption... options) {
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(List.of(options)))
                .isInstanceOf(ValidationException.class).hasMessage(message);
    }

    // ---- count: 1 / 50 / 51 ----

    @Test
    void 성공_옵션이_1개나_50개면_받는다() {
        assertThat(ProductValidation.normalizeOptions(List.of(sizeOnly("M")))).hasSize(1);

        List<NewProductOption> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(sizeOnly("S" + i));
        }
        assertThat(ProductValidation.normalizeOptions(fifty)).hasSize(50);
    }

    @Test
    void 실패_옵션이_51개면_거절한다() {
        List<NewProductOption> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(sizeOnly("S" + i));
        }

        assertThatThrownBy(() -> ProductValidation.normalizeOptions(tooMany))
                .isInstanceOf(ValidationException.class).hasMessage("옵션은 1~50개까지 등록할 수 있어요.");
    }

    @Test
    void 실패_옵션이_없거나_비어_있으면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(List.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_옵션_목록에_null이_있으면_거절한다() {
        List<NewProductOption> withNull = new ArrayList<>();
        withNull.add(sizeOnly("M"));
        withNull.add(null);

        assertThatThrownBy(() -> ProductValidation.normalizeOptions(withNull))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 정보가 올바르지 않아요.");
    }

    // ---- names ----

    @Test
    void 성공_색상_이름과_사이즈는_앞뒤_공백을_다듬고_빈_값은_없는_것으로_본다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(
                List.of(opt("  화이트 ", " #FFFFFF ", " M  ", 1000, null, 3)));

        assertThat(cleaned.get(0).colorName()).isEqualTo("화이트");
        assertThat(cleaned.get(0).sizeLabel()).isEqualTo("M");
        assertThat(cleaned.get(0).colorHex()).isEqualTo("#FFFFFF");
    }

    @Test
    void 성공_공백뿐인_사이즈는_없는_것으로_보고_색상만_있는_옵션이_된다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(List.of(opt("블랙", "", "   ", 1000, null, 3)));

        assertThat(cleaned.get(0).sizeLabel()).isNull();
        assertThat(cleaned.get(0).colorHex()).isNull();
    }

    @Test
    void 성공_이름이_정확히_30자면_받고_31자면_거절한다() {
        String thirty = "가".repeat(30);

        assertThat(ProductValidation.normalizeOptions(List.of(opt(thirty, null, thirty, 1000, null, 1)))).hasSize(1);
        assertRejected("색상 이름과 사이즈는 30자 이하로 입력해 주세요.", opt("가".repeat(31), null, null, 1000, null, 1));
        assertRejected("색상 이름과 사이즈는 30자 이하로 입력해 주세요.", opt(null, null, "가".repeat(31), 1000, null, 1));
    }

    @Test
    void 성공_30자_제한은_다듬은_뒤_길이로_센다() {
        String padded = "  " + "가".repeat(30) + "  ";

        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, padded, 1000, null, 1)))).hasSize(1);
    }

    @Test
    void 실패_색상_이름도_사이즈도_없으면_거절한다() {
        assertRejected("옵션에는 색상 이름이나 사이즈가 하나는 있어야 해요.", opt(null, null, null, 1000, null, 1));
        assertRejected("옵션에는 색상 이름이나 사이즈가 하나는 있어야 해요.", opt("  ", null, "", 1000, null, 1));
    }

    // ---- color hex ----

    @ParameterizedTest
    @ValueSource(strings = {"#FFFFFF", "#ffffff", "#0a1B2c", "#000000"})
    void 성공_색상_코드가_샵_여섯자리_16진수면_받는다(String hex) {
        assertThat(ProductValidation.normalizeOptions(List.of(opt("화이트", hex, null, 1000, null, 1)))
                .get(0).colorHex()).isEqualTo(hex);
    }

    @ParameterizedTest
    @ValueSource(strings = {"#FFF", "FFFFFF", "#GGGGGG", "#FFFFFFF", "#12345", "red", "#ff ff0", "##FFFFF", "#FFFFFG"})
    void 실패_색상_코드_형식이_틀리면_거절한다(String hex) {
        assertRejected("색상 코드는 색상 이름과 함께 #RRGGBB 형식으로 입력해 주세요.", opt("화이트", hex, null, 1000, null, 1));
    }

    @Test
    void 실패_색상_이름_없이_색상_코드만_있으면_거절한다() {
        assertRejected("색상 코드는 색상 이름과 함께 #RRGGBB 형식으로 입력해 주세요.", opt(null, "#FFFFFF", "M", 1000, null, 1));
    }

    @Test
    void 성공_색상_코드_없이_색상_이름만_있어도_받는다() {
        assertThat(ProductValidation.normalizeOptions(List.of(opt("화이트", null, null, 1000, null, 1))).get(0).colorHex()).isNull();
    }

    // ---- price / original price / stock ----

    @Test
    void 성공_가격_0과_재고_0은_받는다() {
        assertThatCode(() -> ProductValidation.normalizeOptions(List.of(opt(null, null, "M", 0, null, 0)))).doesNotThrowAnyException();
    }

    @Test
    void 실패_가격이_없거나_음수면_거절한다() {
        assertRejected("옵션 가격이 올바르지 않습니다.", opt(null, null, "M", null, null, 1));
        assertRejected("옵션 가격이 올바르지 않습니다.", opt(null, null, "M", -1, null, 1));
    }

    @Test
    void 실패_재고가_없거나_음수면_거절한다() {
        assertRejected("옵션 재고 수량이 올바르지 않습니다.", opt(null, null, "M", 1000, null, null));
        assertRejected("옵션 재고 수량이 올바르지 않습니다.", opt(null, null, "M", 1000, null, -1));
    }

    @Test
    void 성공_정가가_판매가보다_크거나_없으면_받는다() {
        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, "M", 1000, 1001, 1))).get(0).originalPrice())
                .isEqualTo(1001);
        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, "M", 1000, null, 1))).get(0).originalPrice()).isNull();
    }

    @Test
    void 실패_정가가_판매가_이하이면_거절한다() {
        assertRejected("옵션 정가는 판매가보다 커야 해요.", opt(null, null, "M", 1000, 1000, 1));
        assertRejected("옵션 정가는 판매가보다 커야 해요.", opt(null, null, "M", 1000, 999, 1));
        assertRejected("옵션 정가는 판매가보다 커야 해요.", opt(null, null, "M", 0, 0, 1));
    }

    // ---- duplicates ----

    @Test
    void 실패_같은_색상과_사이즈_조합은_대소문자와_앞뒤_공백을_무시하고_중복이다() {
        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.",
                opt("White", null, "M", 1000, null, 1), opt(" white ", null, "m", 2000, null, 2));
        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.", sizeOnly("XL"), sizeOnly(" xl"));
        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.",
                opt("블랙", null, null, 1000, null, 1), opt("블랙", "#000000", " ", 1000, null, 1));
    }

    @Test
    void 성공_색상이나_사이즈_중_하나만_달라도_다른_옵션이다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(List.of(
                opt("화이트", null, "M", 1000, null, 1), opt("화이트", null, "L", 1000, null, 1),
                opt("블랙", null, "M", 1000, null, 1)));

        assertThat(cleaned).hasSize(3);
    }

    // ---- dimension consistency ----

    @Test
    void 실패_일부만_색상이_있으면_거절한다() {
        assertRejected("모든 옵션이 색상과 사이즈를 같은 방식으로 입력해야 해요.",
                opt("화이트", null, "M", 1000, null, 1), opt(null, null, "L", 1000, null, 1));
    }

    @Test
    void 실패_일부만_사이즈가_있으면_거절한다() {
        assertRejected("모든 옵션이 색상과 사이즈를 같은 방식으로 입력해야 해요.",
                opt("화이트", null, "M", 1000, null, 1), opt("블랙", null, null, 1000, null, 1));
        assertRejected("모든 옵션이 색상과 사이즈를 같은 방식으로 입력해야 해요.",
                opt("블랙", null, null, 1000, null, 1), opt("화이트", null, "M", 1000, null, 1));
    }

    @Test
    void 성공_모두_색상만_모두_사이즈만_모두_둘_다_있으면_받는다() {
        assertThat(ProductValidation.normalizeOptions(List.of(opt("화이트", null, null, 1, null, 1), opt("블랙", null, null, 1, null, 1)))).hasSize(2);
        assertThat(ProductValidation.normalizeOptions(List.of(sizeOnly("S"), sizeOnly("M")))).hasSize(2);
        assertThat(ProductValidation.normalizeOptions(List.of(opt("화이트", null, "S", 1, null, 1), opt("블랙", null, "M", 1, null, 1)))).hasSize(2);
    }

    // ---- option text: forbidden characters, letters/digits, whitespace, NFC, length units ----

    private static final String BAD_CHAR = "옵션 이름에 쓸 수 없는 문자가 있어요.";

    /** Code points built in Java (no raw invisible characters in the source); each is put in the middle of "화" + cp + "이트". */
    private static final int[] FORBIDDEN_CODE_POINTS = {
            0x200B, 0x200C, 0x200D, 0x200E, 0x200F,                 // zero-width space/joiners, direction marks (Cf)
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,                 // bidi embedding / override
            0x2066, 0x2067, 0x2068, 0x2069,                         // bidi isolates
            0x00A0, 0x2003, 0x202F, 0x3000, 0x2028, 0x2029,         // NBSP-like spaces, line/paragraph separators (Z)
            0x0009, 0x000A, 0x000D, 0x000B, 0x000C, 0x0007, 0x007F, 0x0085, 0x0001, // control characters (Cc)
            0x0000,                                                 // NUL
            0xE000, 0xFEFF, 0xD800, 0x0378                          // private use, BOM, lone surrogate, unassigned
    };

    @Test
    void 실패_옵션_이름_가운데에_제어_서식_공백류_문자가_있으면_거절한다() {
        for (int cp : FORBIDDEN_CODE_POINTS) {
            String text = "화" + (char) cp + "이트";
            assertRejected(BAD_CHAR, opt(text, null, null, 1000, null, 1));
            assertRejected(BAD_CHAR, opt(null, null, text, 1000, null, 1));
        }
    }

    @Test
    void 실패_옵션_이름_앞과_끝에_줄바꿈이_아닌_서식_문자나_NBSP가_있어도_거절한다() {
        for (int cp : new int[]{0x200B, 0x202E, 0xFEFF, 0x00A0, 0x0000}) {
            assertRejected(BAD_CHAR, opt("화이트" + (char) cp, null, null, 1000, null, 1));
            assertRejected(BAD_CHAR, opt((char) cp + "화이트", null, null, 1000, null, 1));
        }
    }

    @Test
    void 실패_NUL이_들어가도_500이_아니라_검증_오류다() {
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(List.of(opt("a\u0000b", null, null, 1000, null, 1))))
                .isInstanceOf(ValidationException.class).hasMessage(BAD_CHAR);
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(List.of(opt("화이트", "#FFFF\u0000F", null, 1000, null, 1))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_앞뒤의_탭과_줄바꿈은_공백처럼_다듬어_없앤다() {
        assertThat(ProductValidation.normalizeOptions(List.of(opt("\t화이트\n", null, null, 1000, null, 1))).get(0).colorName())
                .isEqualTo("화이트");
    }

    @Test
    void 실패_글자나_숫자가_하나도_없으면_거절한다() {
        String message = "옵션 이름에는 글자나 숫자가 하나는 있어야 해요.";
        assertRejected(message, opt("---", null, null, 1000, null, 1));
        assertRejected(message, opt(null, null, "!!", 1000, null, 1));
        assertRejected(message, opt(null, null, "😀", 1000, null, 1));
        assertRejected(message, opt("( )", null, null, 1000, null, 1));
    }

    @Test
    void 성공_글자나_숫자가_하나라도_있으면_기호가_섞여도_받는다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(List.of(
                opt("B&W", null, "2XL (7)", 1000, null, 1), opt("A-1", null, "😀 M", 1000, null, 1)));

        assertThat(cleaned).extracting(NewProductOption::sizeLabel).containsExactly("2XL (7)", "😀 M");
        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, "100", 1000, null, 1)))).hasSize(1);
    }

    @Test
    void 성공_공백만_있는_이름은_없는_것으로_보고_색상이나_사이즈가_없다고_거절한다() {
        assertRejected("옵션에는 색상 이름이나 사이즈가 하나는 있어야 해요.", opt("   ", null, "  ", 1000, null, 1));
    }

    @Test
    void 성공_안쪽의_연속_공백은_한_칸으로_줄여_저장한다() {
        NewProductOption cleaned = ProductValidation.normalizeOptions(List.of(opt("  화이트    블루 ", null, "M   L", 1000, null, 1))).get(0);

        assertThat(cleaned.colorName()).isEqualTo("화이트 블루");
        assertThat(cleaned.sizeLabel()).isEqualTo("M L");
    }

    @Test
    void 실패_연속_공백만_다른_이름은_같은_옵션으로_중복이다() {
        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.",
                opt("화이트  M", null, null, 1000, null, 1), opt("화이트 m", null, null, 1000, null, 1));
        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.",
                opt("화이트", null, "Free   Size", 1000, null, 1), opt("화이트", null, "free size", 1000, null, 1));
    }

    @Test
    void 성공_공백을_줄인_뒤의_길이로_30자를_판단한다() {
        String collapsesToThirty = "가".repeat(15) + "     " + "나".repeat(14); // 15 + 5 spaces + 14 = 34 raw, 30 after collapsing

        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, collapsesToThirty, 1000, null, 1))).get(0).sizeLabel())
                .hasSize(30);
    }

    @Test
    void 성공_NFD로_분해된_한글은_NFC로_합친_뒤_30자_이하면_받고_NFC로_저장한다() {
        String nfc = "가".repeat(30);
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd.length()).isGreaterThan(30); // 60 jamo: would be rejected without NFC

        NewProductOption cleaned = ProductValidation.normalizeOptions(List.of(opt(null, null, nfd, 1000, null, 1))).get(0);

        assertThat(cleaned.sizeLabel()).isEqualTo(nfc);
    }

    @Test
    void 실패_NFC로_합친_뒤에도_31자면_NFD여도_거절한다() {
        String nfd = Normalizer.normalize("가".repeat(31), Normalizer.Form.NFD);

        assertRejected("색상 이름과 사이즈는 30자 이하로 입력해 주세요.", opt(null, null, nfd, 1000, null, 1));
    }

    @Test
    void 실패_NFC와_NFD로만_다른_이름은_중복이다() {
        String nfc = "화이트";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        assertRejected("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요.",
                opt(nfc, null, null, 1000, null, 1), opt(nfd, null, null, 1000, null, 1));
    }

    @Test
    void 실패_이모지는_UTF16_길이로_세므로_16개_32단위는_30자_제한에_걸린다() {
        // Deliberately stricter than the DB: VARCHAR(30) counts code points (16 emoji fit), String.length() counts UTF-16
        // units (2 each). Never looser than the DB, so the DB limit can not be hit. (Letters first: symbols alone fail earlier.)
        String emoji = "a" + "😀".repeat(16); // 33 UTF-16 units, 17 code points
        assertRejected("색상 이름과 사이즈는 30자 이하로 입력해 주세요.", opt(null, null, emoji, 1000, null, 1));
        assertRejected("색상 이름과 사이즈는 30자 이하로 입력해 주세요.", opt(null, null, "😀".repeat(16), 1000, null, 1));
    }

    @Test
    void 성공_이모지가_UTF16_길이로_30단위_이하면_받는다() {
        String ok = "a" + "😀".repeat(14); // 1 + 28 = 29 units
        String edge = "ab" + "😀".repeat(14); // exactly 30 units

        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, ok, 1000, null, 1)))).hasSize(1);
        assertThat(ProductValidation.normalizeOptions(List.of(opt(null, null, edge, 1000, null, 1)))).hasSize(1);
    }

    @Test
    void 성공_정리된_목록은_요청_순서를_그대로_유지한다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(List.of(sizeOnly("XL"), sizeOnly("S"), sizeOnly("M")));

        assertThat(cleaned).extracting(NewProductOption::sizeLabel).containsExactly("XL", "S", "M");
    }
}
