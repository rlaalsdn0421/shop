package com.shop.backend.application.service;

import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.OptionEdit;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductImage;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.StockChangedException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductImageRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.Query;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** updateProduct without a database: ordering of checks and locks, error mapping. Row-level effects are in ProductEditIntegrationTest. */
class ProductServiceEditTest {

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductImageRepository imageRepository = mock(ProductImageRepository.class);
    private final ProductOptionRepository optionRepository = mock(ProductOptionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final ProductService service = new ProductService(repository, imageRepository, optionRepository,
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC), em);

    private static final List<String> URLS = List.of("https://img.example.com/a.png");

    @BeforeEach
    void stubLockTimeoutStatement() {
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
    }

    private Product plain(int stock) {
        Product product = new Product("옛이름", "옛설명", 1000, "https://img.example.com/old.png", stock);
        ReflectionTestUtils.setField(product, "id", "p1");
        when(em.find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE)).thenReturn(product);
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of());
        return product;
    }

    private ProductOption option(String id, int stock) {
        ProductOption option = new ProductOption("p1", new NewProductOption("화이트", null, id, 1000, null, stock), 0);
        ReflectionTestUtils.setField(option, "id", id);
        when(em.find(ProductOption.class, id, LockModeType.PESSIMISTIC_WRITE)).thenReturn(option);
        return option;
    }

    private Product update(Integer price, Integer stock, Integer expected) {
        return service.updateProduct("p1", "새이름", "새설명", null, null, URLS, price, null, stock, expected, null, "u1");
    }

    @Test
    void 성공_옵션_없는_상품은_행을_잠그고_바뀐_재고를_저장하며_이미지를_다시_쓴다() {
        Product product = plain(10);

        update(1500, 8, 10);

        assertThat(product.getName()).isEqualTo("새이름");
        assertThat(product.getPrice()).isEqualTo(1500);
        assertThat(product.getStock()).isEqualTo(8);
        InOrder order = inOrder(em, imageRepository);
        order.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        order.verify(em).find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE);
        order.verify(imageRepository).deleteAllByProductId("p1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductImage>> rows = ArgumentCaptor.forClass(List.class);
        order.verify(imageRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(ProductImage::getUrl).containsExactly("https://img.example.com/a.png");
        assertThat(rows.getValue()).extracting(ProductImage::getSortOrder).containsExactly(0);
    }

    @Test
    void 성공_재고를_바꾸지_않은_상품은_그_사이_바뀐_현재_재고를_덮어쓰지_않는다() {
        Product product = plain(7); // an order took 3 of the 10 the admin saw

        update(1500, 10, 10);

        assertThat(product.getStock()).isEqualTo(7);
        assertThat(product.getPrice()).isEqualTo(1500);
    }

    @Test
    void 실패_재고가_그_사이_바뀌었으면_409용_예외이고_상품은_수정되지_않는다() {
        Product product = plain(7);

        assertThatThrownBy(() -> update(1500, 20, 10)).isInstanceOf(StockChangedException.class);

        assertThat(product.getName()).isEqualTo("옛이름");
        assertThat(product.getStock()).isEqualTo(7);
        verify(imageRepository, never()).deleteAllByProductId(anyString());
        verify(imageRepository, never()).saveAll(any());
    }

    @Test
    void 실패_없는_상품은_404용_예외이고_아무것도_지우지_않는다() {
        when(em.find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);

        assertThatThrownBy(() -> update(1500, 8, 10)).isInstanceOf(ProductNotFoundException.class);

        verify(imageRepository, never()).deleteAllByProductId(anyString());
    }

    @Test
    void 실패_검증_오류가_재고_충돌보다_먼저다() {
        Product product = plain(7);

        // stock changed (would be 409) but the price is invalid (400) -> 400
        assertThatThrownBy(() -> update(-1, 20, 10)).isInstanceOf(ValidationException.class);
        // invalid image list
        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, List.of(), 1500, null, 20, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        // invalid name
        assertThatThrownBy(() -> service.updateProduct("p1", " ", "새설명", null, null, URLS, 1500, null, 20, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        // category not in the list
        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", "없는분류", null, URLS, 1500, null, 20, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        // original price not above the price
        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, URLS, 1500, 1500, 20, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        // no expectedStock
        assertThatThrownBy(() -> update(1500, 20, null)).isInstanceOf(ValidationException.class);

        assertThat(product.getName()).isEqualTo("옛이름");
        verify(imageRepository, never()).deleteAllByProductId(anyString());
    }

    @Test
    void 실패_상품_행_잠금이_시간_초과면_503으로_매핑되는_잠금_예외로_바뀐다() {
        when(em.find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE)).thenThrow(new LockTimeoutException("55P03"));

        assertThatThrownBy(() -> update(1500, 8, 10)).isInstanceOf(PessimisticLockingFailureException.class);
        verify(imageRepository, never()).deleteAllByProductId(anyString());
    }

    @Test
    void 실패_옵션_행_잠금이_실패해도_잠금_예외로_바뀐다() {
        Product product = plain(0);
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of("o1"));
        when(em.find(ProductOption.class, "o1", LockModeType.PESSIMISTIC_WRITE)).thenThrow(new PessimisticLockException("40P01"));

        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, URLS, null, null, null, null,
                List.of(new OptionEdit("o1", 1000, null, 3, 3)), "u1"))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(product.getName()).isEqualTo("옛이름");
    }

    @Test
    void 성공_옵션_행은_sort_order가_아니라_옵션_id_문자열_오름차순으로_한_행씩_잠근다() {
        Product product = plain(0);
        // display order b, a, c ; lock order must be a, b, c (the order creation uses)
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of("b", "a", "c"));
        option("a", 1);
        option("b", 2);
        option("c", 3);

        service.updateProduct("p1", "새이름", "새설명", null, null, URLS, null, null, null, null,
                List.of(new OptionEdit("c", 3000, null, 3, 3), new OptionEdit("a", 1000, null, 1, 1),
                        new OptionEdit("b", 2000, null, 2, 2)), "u1");

        InOrder order = inOrder(em);
        order.verify(em).find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).find(ProductOption.class, "a", LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).find(ProductOption.class, "b", LockModeType.PESSIMISTIC_WRITE);
        order.verify(em).find(ProductOption.class, "c", LockModeType.PESSIMISTIC_WRITE);
        assertThat(product.getPrice()).isEqualTo(1000);
    }

    @Test
    void 실패_옵션_하나의_재고가_충돌하면_예외이고_상품_필드는_바뀌지_않는다() {
        Product product = plain(0);
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of("a", "b"));
        ProductOption a = option("a", 5);
        ProductOption b = option("b", 4); // sold 1 since the admin saw 5

        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, URLS, null, null, null, null,
                List.of(new OptionEdit("a", 1000, null, 9, 5), new OptionEdit("b", 2000, null, 9, 5)), "u1"))
                .isInstanceOf(StockChangedException.class);

        assertThat(product.getName()).isEqualTo("옛이름");
        verify(imageRepository, never()).deleteAllByProductId(anyString());
        assertThat(b.getStock()).isEqualTo(4);
        assertThat(a.getStock()).isEqualTo(9); // in memory only; the transaction rolls back (real DB: ProductEditIntegrationTest)
    }

    @Test
    void 실패_옵션_상품에_옵션_없이_최상위_값만_보내면_검증_오류다() {
        plain(0);
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of("a"));

        assertThatThrownBy(() -> update(1500, 8, 10)).isInstanceOf(ValidationException.class);
        verify(em, never()).find(eq(ProductOption.class), anyString(), any(LockModeType.class));
    }

    // ---- pure input rules run before any lock ----

    private Product call(String name, String description, String category, List<String> tags, List<String> urls) {
        return service.updateProduct("p1", name, description, category, tags, urls, 1500, null, 10, 10, null, "u1");
    }

    @Test
    void 실패_순수_입력_오류는_락도_SET_LOCAL도_조회도_하기_전에_검증_오류로_끝난다() {
        plain(10);
        Map<String, Runnable> bad = new LinkedHashMap<>();
        bad.put("blank name", () -> call(" ", "d", null, null, URLS));
        bad.put("null name", () -> call(null, "d", null, null, URLS));
        bad.put("name 201 chars", () -> call("가".repeat(201), "d", null, null, URLS));
        bad.put("blank description", () -> call("n", "", null, null, URLS));
        bad.put("description 2001 chars", () -> call("n", "가".repeat(2001), null, null, URLS));
        bad.put("unknown category", () -> call("n", "d", "없는분류", null, URLS));
        bad.put("empty images", () -> call("n", "d", null, null, List.of()));
        bad.put("null images", () -> call("n", "d", null, null, null));
        bad.put("11 images", () -> call("n", "d", null, null,
                IntStream.range(0, 11).mapToObj(i -> "https://img.example.com/" + i).toList()));
        bad.put("image without http scheme", () -> call("n", "d", null, null, List.of("ftp://img.example.com/a.png")));
        bad.put("image url 2001 chars", () -> call("n", "d", null, null, List.of("https://" + "a".repeat(1993))));
        bad.put("4 hashtags", () -> call("n", "d", null, List.of("a", "b", "c", "d"), URLS));
        bad.put("hashtag with space", () -> call("n", "d", null, List.of("a b"), URLS));

        bad.forEach((label, task) -> assertThatThrownBy(task::run).as(label).isInstanceOf(ValidationException.class));

        verifyNoInteractions(em, optionRepository, imageRepository, repository);
    }

    @Test
    void 실패_없는_상품이어도_본문이_틀리면_상품_없음이_아니라_검증_오류이고_락을_잡지_않는다() {
        when(em.find(Product.class, "p1", LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);

        assertThatThrownBy(() -> call(" ", "d", null, null, URLS)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(em, optionRepository, imageRepository);

        // a valid body for the same missing product is the not-found
        assertThatThrownBy(() -> call("n", "d", null, null, URLS)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void 성공_카테고리는_앞뒤_공백을_지우고_저장되며_빈_카테고리는_null이다() {
        Product product = plain(10);

        call("n", "d", " 신발 ", null, URLS);
        assertThat(product.getCategory()).isEqualTo("신발");

        call("n", "d", "  ", null, URLS);
        assertThat(product.getCategory()).isNull();
    }

    // ---- stock cap and option cap (checked after the lock, still a 400 and still before any change) ----

    @Test
    void 성공_재고_100만은_저장되고_100만1은_충돌보다_먼저_검증_오류이며_아무것도_바뀌지_않는다() {
        Product product = plain(10);

        assertThatThrownBy(() -> update(1500, 1_000_001, 10)).isInstanceOf(ValidationException.class)
                .hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
        assertThat(product.getStock()).isEqualTo(10);
        assertThat(product.getName()).isEqualTo("옛이름");
        verify(imageRepository, never()).deleteAllByProductId(anyString());

        update(1500, 1_000_000, 10);
        assertThat(product.getStock()).isEqualTo(1_000_000);
    }

    @Test
    void 실패_옵션_재고가_100만을_넘거나_옵션이_51개면_옵션_행을_잠그기_전에_검증_오류다() {
        plain(0);
        when(optionRepository.findIdsByProductIdOrderBySortOrder("p1")).thenReturn(List.of("a", "b"));

        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, URLS, null, null, null, null,
                List.of(new OptionEdit("a", 1000, null, 1_000_001, 5), new OptionEdit("b", 2000, null, 1, 1)), "u1"))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");

        List<OptionEdit> tooMany = IntStream.range(0, 51).mapToObj(i -> new OptionEdit("o" + i, 1000, null, 1, 1)).toList();
        assertThatThrownBy(() -> service.updateProduct("p1", "새이름", "새설명", null, null, URLS, null, null, null, null, tooMany, "u1"))
                .isInstanceOf(ValidationException.class).hasMessage("옵션은 1~50개까지 등록할 수 있어요.");

        verify(em, never()).find(eq(ProductOption.class), anyString(), any(LockModeType.class));
        verify(imageRepository, never()).deleteAllByProductId(anyString());
    }

    // ---- audit log ----

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ProductService.class);
    private ListAppender<ILoggingEvent> appender;

    private ListAppender<ILoggingEvent> captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void detachLogs() {
        if (appender != null) {
            serviceLogger.detachAppender(appender);
        }
    }

    @Test
    void 성공_수정_로그에는_누가_어떤_가격으로_바꿨는지만_남고_이름_설명_이미지_해시태그는_없다() {
        plain(10);
        ListAppender<ILoggingEvent> logs = captureLogs();

        service.updateProduct("p1", "비밀이름XYZ", "비밀설명XYZ", "신발", List.of("비밀태그XYZ"),
                List.of("https://img.example.com/비밀XYZ.png"), 1500, null, 4, 10, null, "seller-7");
        service.updateProduct("p1", "비밀이름XYZ", "비밀설명XYZ", null, null, URLS, 1500, null, 4, 4, null, "admin-9");

        assertThat(logs.list).hasSize(2);
        ILoggingEvent first = logs.list.get(0);
        assertThat(first.getLevel()).isEqualTo(Level.INFO);
        assertThat(first.getFormattedMessage()).startsWith("Product edited:")
                .contains("productId=p1").contains("actor=seller-7").contains("price=1000->1500")
                .contains("stockChanges=1").contains("options=0").contains("images=1");
        assertThat(first.getFormattedMessage()).doesNotContain("XYZ").doesNotContain("신발").doesNotContain("img.example.com");
        // second edit: another actor, price now unchanged, stock not touched
        assertThat(logs.list.get(1).getFormattedMessage()).contains("actor=admin-9").contains("price=1500->1500")
                .contains("stockChanges=0").doesNotContain("seller-7").doesNotContain("XYZ");
    }

    @Test
    void 실패_검증이나_충돌로_끝난_수정은_수정_로그를_남기지_않는다() {
        plain(7);
        ListAppender<ILoggingEvent> logs = captureLogs();

        assertThatThrownBy(() -> update(1500, 20, 10)).isInstanceOf(StockChangedException.class);
        assertThatThrownBy(() -> call(" ", "d", null, null, URLS)).isInstanceOf(ValidationException.class);

        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Product edited"));
    }
}
