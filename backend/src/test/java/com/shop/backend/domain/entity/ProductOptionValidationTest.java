package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void 성공_정리된_목록은_요청_순서를_그대로_유지한다() {
        List<NewProductOption> cleaned = ProductValidation.normalizeOptions(List.of(sizeOnly("XL"), sizeOnly("S"), sizeOnly("M")));

        assertThat(cleaned).extracting(NewProductOption::sizeLabel).containsExactly("XL", "S", "M");
    }
}
