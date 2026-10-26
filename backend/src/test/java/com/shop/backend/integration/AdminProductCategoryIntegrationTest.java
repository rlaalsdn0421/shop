package com.shop.backend.integration;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.presentation.controller.AdminProductController;
import com.shop.backend.presentation.dto.ProductDtos;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for "products registered by an admin were saved without category and so missed the category filter":
 * goes through the real AdminProductController (request DTO -> service -> products.category) on a real Postgres and
 * reads back both the column and the storefront filter (listProducts).
 */
class AdminProductCategoryIntegrationTest extends PostgresIntegrationTest {

    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("admin-1", null);

    private AdminProductController controller() {
        return new AdminProductController(productService);
    }

    private static ProductDtos.NewProductRequest newRequest(String name, String category) {
        return new ProductDtos.NewProductRequest(name, "설명-" + name, 5000, "https://img.example.com/" + name + ".png", null, 10,
                category, null, null, null);
    }

    private String create(String name, String category) {
        return controller().createProduct(newRequest(name, category)).id();
    }

    private ProductDtos.UpdateProductRequest editRequest(String name, String category) {
        return new ProductDtos.UpdateProductRequest(name, "설명-" + name, category, null,
                List.of("https://img.example.com/" + name + ".png"), 5000, null, 10, 10, null);
    }

    private List<String> listedIds(String category, ProductSort sort) {
        return productService.listProducts(category, sort, 0, 50).getContent().stream().map(Product::getId).toList();
    }

    private String categoryColumn(String id) {
        return jdbc.queryForObject("SELECT category FROM products WHERE id = ?", String.class, id);
    }

    @Test
    void 성공_컨트롤러로_카테고리를_넣어_등록하면_저장되고_그_카테고리_필터에만_나온다() {
        String id = create("운동화", "신발");

        assertThat(categoryColumn(id)).isEqualTo("신발");
        ProductSort defaultSort = ProductSort.parse(null);
        assertThat(listedIds("신발", defaultSort)).containsExactly(id);
        assertThat(listedIds("바지", defaultSort)).doesNotContain(id).isEmpty();
        assertThat(listedIds(null, defaultSort)).containsExactly(id);
        assertThat(listedIds("신발", ProductSort.PRICE_ASC)).containsExactly(id);
        assertThat(listedIds("바지", ProductSort.PRICE_ASC)).isEmpty();
    }

    @Test
    void 성공_카테고리_없이_등록하면_NULL로_저장되고_필터에는_안_나오고_전체_목록에만_나온다() {
        String omitted = create("무분류", null);
        String blank = create("공백분류", "   ");

        assertThat(categoryColumn(omitted)).isNull();
        assertThat(categoryColumn(blank)).isNull();
        ProductSort defaultSort = ProductSort.parse(null);
        assertThat(listedIds(null, defaultSort)).containsExactlyInAnyOrder(omitted, blank);
        assertThat(listedIds("신발", defaultSort)).isEmpty();
        assertThat(listedIds("   ", defaultSort)).containsExactlyInAnyOrder(omitted, blank); // blank filter = no filter
    }

    @Test
    void 실패_카테고리를_다른_필드로_밀려_저장하지_않는다_이름_설명_카테고리가_각자_자리에_있다() {
        String id = create("운동화", "신발");

        var row = jdbc.queryForMap("SELECT name, description, category FROM products WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("운동화");
        assertThat(row.get("description")).isEqualTo("설명-운동화");
        assertThat(row.get("category")).isEqualTo("신발");
    }

    @Test
    void 성공_컨트롤러로_수정하면_카테고리가_바뀌고_필터_목록이_따라간다() {
        String id = create("운동화", "신발");
        String other = create("청바지", "바지");
        ProductSort defaultSort = ProductSort.parse(null);

        controller().updateProduct(id, editRequest("운동화", "가방"), admin);

        assertThat(categoryColumn(id)).isEqualTo("가방");
        assertThat(listedIds("가방", defaultSort)).containsExactly(id);
        assertThat(listedIds("신발", defaultSort)).isEmpty();
        assertThat(listedIds("바지", defaultSort)).containsExactly(other);
        assertThat(categoryColumn(other)).isEqualTo("바지");
    }

    @Test
    void 성공_수정에서_카테고리를_비우면_NULL이_되어_필터에서_빠진다() {
        String id = create("운동화", "신발");

        controller().updateProduct(id, editRequest("운동화", null), admin);

        assertThat(categoryColumn(id)).isNull();
        assertThat(listedIds("신발", ProductSort.parse(null))).isEmpty();
        assertThat(listedIds(null, ProductSort.parse(null))).containsExactly(id);
    }
}
