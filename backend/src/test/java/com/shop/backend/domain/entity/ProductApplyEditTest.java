package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** applyEdit replaces every editable field and nothing else (id, created_at, option labels stay). */
class ProductApplyEditTest {

    @Test
    void 성공_상품_수정은_모든_수정_필드를_바꾼다() {
        Product product = new Product("옛이름", "옛설명", 1000, "http://img/old", 7, "신발", 2000, List.of("옛태그"), true);

        product.applyEdit("새이름", "새설명", 1500, "http://img/new", 9, "가방", 3000, List.of("a", "b"), false);

        assertThat(product.getName()).isEqualTo("새이름");
        assertThat(product.getDescription()).isEqualTo("새설명");
        assertThat(product.getPrice()).isEqualTo(1500);
        assertThat(product.getImageUrl()).isEqualTo("http://img/new");
        assertThat(product.getStock()).isEqualTo(9);
        assertThat(product.getCategory()).isEqualTo("가방");
        assertThat(product.getOriginalPrice()).isEqualTo(3000);
        assertThat(product.getHashtags()).containsExactly("a", "b");
        assertThat(product.isPriceVaries()).isFalse();
    }

    @Test
    void 성공_상품_수정은_정가_카테고리_해시태그를_비울_수_있고_가격차이_표시를_켠다() {
        Product product = new Product("n", "d", 1000, "http://img/old", 7, "신발", 2000, List.of("옛태그"), false);

        product.applyEdit("n", "d", 1000, "http://img/old", 7, null, null, null, true);

        assertThat(product.getCategory()).isNull();
        assertThat(product.getOriginalPrice()).isNull();
        assertThat(product.getHashtags()).isEmpty();
        assertThat(product.isPriceVaries()).isTrue();
    }

    @Test
    void 성공_빈_해시태그_목록도_빈_목록으로_저장된다() {
        Product product = new Product("n", "d", 1000, "http://img/old", 7, null, null, List.of("x"));

        product.applyEdit("n", "d", 1000, "http://img/old", 7, null, null, List.of(), false);

        assertThat(product.getHashtags()).isEmpty();
    }

    @Test
    void 성공_옵션_수정은_가격_정가_재고만_바꾸고_이름과_순서는_그대로다() {
        ProductOption option = new ProductOption("p1", new NewProductOption("화이트", "#FFFFFF", "M", 1000, 1500, 4), 3);

        option.applyEdit(1200, null, 9);

        assertThat(option.getPrice()).isEqualTo(1200);
        assertThat(option.getOriginalPrice()).isNull();
        assertThat(option.getStock()).isEqualTo(9);
        assertThat(option.getColorName()).isEqualTo("화이트");
        assertThat(option.getColorHex()).isEqualTo("#FFFFFF");
        assertThat(option.getSizeLabel()).isEqualTo("M");
        assertThat(option.getSortOrder()).isEqualTo(3);
        assertThat(option.getProductId()).isEqualTo("p1");
    }
}
