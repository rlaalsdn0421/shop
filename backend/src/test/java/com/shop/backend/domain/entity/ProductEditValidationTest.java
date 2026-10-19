package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Product edit rules: per-option numbers, expectedStock, and the plain-vs-option shape of the request. */
class ProductEditValidationTest {

    private static final List<String> NONE = List.of();
    private static final List<String> OPTION_IDS = List.of("o1", "o2");

    private static OptionEdit edit(String id, Integer price, Integer original, Integer stock, Integer expected) {
        return new OptionEdit(id, price, original, stock, expected);
    }

    private static OptionEdit ok(String id) {
        return edit(id, 1000, null, 3, 3);
    }

    private static List<OptionEdit> list(OptionEdit... edits) {
        return new ArrayList<>(Arrays.asList(edits)); // allows null elements
    }

    // ---- validateOptionPriceStock ----

    @Test
    void 성공_옵션_가격_정가_재고의_경계값은_통과한다() {
        assertThatCode(() -> ProductValidation.validateOptionPriceStock(0, null, 0)).doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateOptionPriceStock(1000, 1001, 5)).doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateOptionPriceStock(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1_000_000))
                .doesNotThrowAnyException();
    }

    @Test
    void 성공_옵션_재고는_100만까지_통과하고_100만1부터_거절한다() {
        assertThat(ProductValidation.MAX_STOCK).isEqualTo(1_000_000);
        assertThatCode(() -> ProductValidation.validateOptionPriceStock(1000, null, 1_000_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, null, 1_000_001))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, null, Integer.MAX_VALUE))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
    }

    @Test
    void 성공_상품_재고도_100만까지_통과하고_100만1부터_거절한다() {
        assertThatCode(() -> ProductValidation.validateNewProduct("n", "d", 1000, "http://img", 1_000_000, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateNewProduct("n", "d", 1000, "http://img", 0, null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductValidation.validateNewProduct("n", "d", 1000, "http://img", 1_000_001, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
        assertThatThrownBy(() -> ProductValidation.validateNewProduct("n", "d", 1000, "http://img", Integer.MAX_VALUE, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
        assertThatThrownBy(() -> ProductValidation.validateNewProduct("n", "d", 1000, "http://img", -1, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량이 올바르지 않습니다.");
    }

    @Test
    void 성공_새_옵션_등록도_재고_100만까지_통과하고_100만1은_거절한다() {
        assertThat(ProductValidation.normalizeOptions(List.of(new NewProductOption("화이트", null, "M", 1000, null, 1_000_000))))
                .hasSize(1);
        assertThatThrownBy(() -> ProductValidation.normalizeOptions(
                List.of(new NewProductOption("화이트", null, "M", 1000, null, 1_000_001))))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
    }

    @Test
    void 성공_이름_설명_이미지_URL_규칙과_카테고리_규칙은_따로_검사된다() {
        assertThatCode(() -> ProductValidation.validateProductText("a".repeat(200), "d".repeat(2000), "http://img"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductValidation.validateProductText("a".repeat(201), "d", "http://img"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateProductText("n", "d".repeat(2001), "http://img"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateProductText(" ", "d", "http://img"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateProductText("n", null, "http://img"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateProductText("n", "d", " "))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> ProductValidation.validateCategory(null)).doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateCategory("")).doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateCategory("신발")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductValidation.validateCategory("없는분류"))
                .isInstanceOf(ValidationException.class).hasMessage("올바르지 않은 카테고리입니다.");
    }

    @Test
    void 실패_옵션_가격이_없거나_음수면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(null, null, 1))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 가격이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(-1, null, 1))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 가격이 올바르지 않습니다.");
    }

    @Test
    void 실패_옵션_정가가_판매가_이하면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, 1000, 1))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 정가는 판매가보다 커야 해요.");
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, 999, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(0, 0, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(0, -5, 1))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_옵션_재고가_없거나_음수면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 재고 수량이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateOptionPriceStock(1000, null, -1))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 재고 수량이 올바르지 않습니다.");
    }

    // ---- validateExpectedStock ----

    @Test
    void 성공_기준_재고는_0이상이면_통과한다() {
        assertThatCode(() -> ProductValidation.validateExpectedStock(0)).doesNotThrowAnyException();
        assertThatCode(() -> ProductValidation.validateExpectedStock(Integer.MAX_VALUE)).doesNotThrowAnyException();
    }

    @Test
    void 실패_기준_재고가_없거나_음수면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateExpectedStock(null))
                .isInstanceOf(ValidationException.class).hasMessage("기준 재고 값이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateExpectedStock(-1))
                .isInstanceOf(ValidationException.class);
    }

    // ---- validateEditOptions: product without options ----

    @Test
    void 성공_옵션_없는_상품은_기준_재고만_있으면_빈_맵을_돌려준다() {
        assertThat(ProductValidation.validateEditOptions(NONE, 1000, null, 5, 5, null)).isEmpty();
        assertThat(ProductValidation.validateEditOptions(NONE, 1000, null, 5, 0, List.of())).isEmpty();
    }

    @Test
    void 실패_옵션_없는_상품에_옵션을_보내면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(NONE, 1000, null, 5, 5, list(ok("o1"))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("옵션이 없어서");
    }

    @Test
    void 실패_옵션_없는_상품의_기준_재고가_없거나_음수면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(NONE, 1000, null, 5, null, null))
                .isInstanceOf(ValidationException.class).hasMessage("기준 재고 값이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(NONE, 1000, null, 5, -1, null))
                .isInstanceOf(ValidationException.class);
    }

    // ---- validateEditOptions: product with options ----

    @Test
    void 성공_모든_옵션을_한_번씩_보내면_id별_맵을_돌려준다() {
        OptionEdit second = edit("o2", 2000, 2500, 0, 4);
        OptionEdit first = ok("o1");

        Map<String, OptionEdit> result = ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, list(second, first));

        assertThat(result).containsOnlyKeys("o1", "o2");
        assertThat(result.get("o1")).isSameAs(first);
        assertThat(result.get("o2")).isSameAs(second);
    }

    @Test
    void 실패_옵션_있는_상품에_최상위_가격_정가_재고_기준재고를_보내면_각각_거절한다() {
        List<OptionEdit> all = list(ok("o1"), ok("o2"));
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, 1000, null, null, null, all))
                .isInstanceOf(ValidationException.class).hasMessageContaining("옵션별");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, 2000, null, null, all))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, 0, null, all))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, 0, all))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_옵션_있는_상품에_옵션_목록이_없거나_비어_있으면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("모든 옵션");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_옵션_항목이_null이거나_id가_없으면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, list(ok("o1"), null)))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 정보가 올바르지 않아요.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, list(ok("o1"), ok(null))))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 정보가 올바르지 않아요.");
    }

    @Test
    void 실패_같은_옵션_id가_두_번_오면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), ok("o1"))))
                .isInstanceOf(ValidationException.class).hasMessage("같은 옵션이 중복되어 있어요.");
    }

    @Test
    void 실패_빠진_옵션이_있거나_모르는_옵션이_있으면_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, list(ok("o1"))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("옵션 목록이 상품의 옵션과 맞지 않아요");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), ok("o2"), ok("o3"))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("옵션 목록이 상품의 옵션과 맞지 않아요");
        // same count, but one id belongs to another product
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), ok("other"))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("옵션 목록이 상품의 옵션과 맞지 않아요");
    }

    @Test
    void 실패_옵션_값이_틀리면_해당_규칙으로_거절한다() {
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", -1, null, 1, 1))))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 가격이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", 1000, 1000, 1, 1))))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 정가는 판매가보다 커야 해요.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", 1000, null, -1, 1))))
                .isInstanceOf(ValidationException.class).hasMessage("옵션 재고 수량이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", 1000, null, 1, null))))
                .isInstanceOf(ValidationException.class).hasMessage("기준 재고 값이 올바르지 않습니다.");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", 1000, null, 1, -1))))
                .isInstanceOf(ValidationException.class).hasMessage("기준 재고 값이 올바르지 않습니다.");
    }

    @Test
    void 성공_옵션_값_경계값_가격0_재고0_기준재고0은_통과한다() {
        assertThat(ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(edit("o1", 0, null, 0, 0), edit("o2", 0, 1, 0, 0)))).hasSize(2);
    }

    @Test
    void 성공_옵션_재고_100만은_통과하고_100만1은_거절하며_기준_재고는_100만을_넘어도_된다() {
        assertThat(ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(edit("o1", 1000, null, 1_000_000, 2_000_000), ok("o2")))).hasSize(2);
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null,
                list(ok("o1"), edit("o2", 1000, null, 1_000_001, 3))))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
    }

    @Test
    void 성공_옵션_50개까지는_통과하고_51개부터는_다른_검사보다_먼저_거절한다() {
        List<String> fifty = IntStream.range(0, 50).mapToObj(i -> "o" + i).toList();
        List<OptionEdit> fiftyEdits = fifty.stream().map(ProductEditValidationTest::ok).toList();
        assertThat(ProductValidation.validateEditOptions(fifty, null, null, null, null, fiftyEdits)).hasSize(50);

        List<OptionEdit> fiftyOne = new ArrayList<>(fiftyEdits);
        fiftyOne.add(ok("o50"));
        List<String> fiftyOneIds = IntStream.range(0, 51).mapToObj(i -> "o" + i).toList();
        String cap = "옵션은 1~50개까지 등록할 수 있어요.";
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(fiftyOneIds, null, null, null, null, fiftyOne))
                .isInstanceOf(ValidationException.class).hasMessage(cap);
        // cap first: not the "no options" / "top-level values" / "null item" messages
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(NONE, 1000, null, 5, 5, fiftyOne))
                .isInstanceOf(ValidationException.class).hasMessage(cap);
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, 1000, null, 5, 5, fiftyOne))
                .isInstanceOf(ValidationException.class).hasMessage(cap);
        List<OptionEdit> nulls = new ArrayList<>(Collections.nCopies(51, (OptionEdit) null));
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(OPTION_IDS, null, null, null, null, nulls))
                .isInstanceOf(ValidationException.class).hasMessage(cap);
    }

    @Test
    void 성공_옵션_하나짜리_상품도_그_옵션을_보내야_한다() {
        assertThat(ProductValidation.validateEditOptions(List.of("only"), null, null, null, null, list(ok("only"))))
                .containsOnlyKeys("only");
        assertThatThrownBy(() -> ProductValidation.validateEditOptions(List.of("only"), null, null, null, null, list(ok("x"))))
                .isInstanceOf(ValidationException.class);
    }
}
