package com.shop.backend.application.service;

import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.entity.ShippingPolicy;
import com.shop.backend.domain.entity.StockKey;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Orders with option lines (V18): server-side option rules, option price/stock, snapshot, replay. Plain Mockito. */
@ExtendWith(MockitoExtension.class)
class OrderServiceOptionsTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductOptionRepository productOptionRepository;
    @Mock
    private OrderRepository orderRepository;
    private static final String USER = "user-1";
    private static final String KEY = "key-12345";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private static Product product(String id, int price, int stock) {
        Product product = new Product("상품 " + id, "설명", price, "http://img/" + id, stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private static ProductOption option(String id, String productId, String color, String size, int price, int stock) {
        ProductOption option = new ProductOption(productId,
                new NewProductOption(color, null, size, price, null, stock), 0);
        ReflectionTestUtils.setField(option, "id", id);
        return option;
    }

    private OrderService service() {
        return new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK);
    }

    private Order create(OrderLine... lines) {
        return service().createOrder(USER, KEY, "구매자", "010-0000-0000", "서울", List.of(lines));
    }

    private void givenProducts(Product... products) {
        when(productRepository.findAllByIdIn(anyList())).thenReturn(List.of(products));
    }

    private void givenOptions(ProductOption... options) {
        when(productOptionRepository.findByProductIdIn(anyCollection())).thenReturn(List.of(options));
    }

    private void givenUpdatesSucceed() {
        lenient().when(productRepository.decrementStockIfAvailable(anyString(), anyInt())).thenReturn(1);
        lenient().when(productOptionRepository.decrementStockIfAvailable(anyString(), anyString(), anyInt())).thenReturn(1);
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());
    }

    // ---- price, snapshot, stock rows ----

    @Test
    void 성공_옵션_줄은_옵션_가격으로_계산하고_항목에_옵션_id와_이름표를_저장한다() {
        givenProducts(product("p1", 1000, 0)); // the product price is the cheapest, not what an option sells for
        givenOptions(option("o1", "p1", "화이트", "M", 30_000, 5), option("o2", "p1", "블랙", "L", 40_000, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1, "o2"), new OrderLine("p1", 1, "o1"));

        assertThat(order.getTotalAmount()).isEqualTo(70_000);
        assertThat(order.getShippingFee()).isZero();
        assertThat(order.getItems()).extracting(OrderItem::getPrice).containsExactlyInAnyOrder(30_000, 40_000);
        assertThat(order.getItems()).extracting(OrderItem::getOptionId).containsExactly("o1", "o2");
        assertThat(order.getItems()).extracting(OrderItem::getOptionLabel).containsExactly("화이트 / M", "블랙 / L");
    }

    @Test
    void 성공_배송비는_옵션_가격_합계로_판단한다() {
        givenProducts(product("p1", 100, 0));
        givenOptions(option("o1", "p1", null, "M", 49_999, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1, "o1"));

        assertThat(order.getShippingFee()).isEqualTo(ShippingPolicy.FEE);
        assertThat(order.getTotalAmount()).isEqualTo(49_999 + ShippingPolicy.FEE);
    }

    @Test
    void 성공_옵션_줄은_옵션_재고만_조건부로_줄이고_상품_재고는_건드리지_않는다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", "화이트", "M", 30_000, 5));
        givenUpdatesSucceed();

        create(new OrderLine("p1", 2, "o1"));

        verify(productOptionRepository).decrementStockIfAvailable("o1", "p1", 2);
        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    @Test
    void 성공_옵션_없는_상품의_줄은_상품_재고만_줄이고_스냅샷은_비어_있다() {
        givenProducts(product("p1", 1000, 5));
        givenOptions();
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 2));

        verify(productRepository).decrementStockIfAvailable("p1", 2);
        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
        assertThat(order.getItems().get(0).getOptionId()).isNull();
        assertThat(order.getItems().get(0).getOptionLabel()).isNull();
        assertThat(order.getItems().get(0).getPrice()).isEqualTo(1000);
    }

    @Test
    void 성공_같은_옵션의_여러_줄은_합쳐서_한_번에_확인하고_차감한다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", null, "M", 1000, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 2, "o1"), new OrderLine("p1", 3, "o1"));

        verify(productOptionRepository).decrementStockIfAvailable("o1", "p1", 5);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void 성공_같은_상품의_다른_옵션은_따로_차감하고_항목도_따로_만든다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", null, "M", 1000, 5), option("o2", "p1", null, "L", 1500, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1, "o1"), new OrderLine("p1", 1, "o2"));

        verify(productOptionRepository).decrementStockIfAvailable("o1", "p1", 1);
        verify(productOptionRepository).decrementStockIfAvailable("o2", "p1", 1);
        assertThat(order.getItems()).hasSize(2);
    }

    @Test
    void 성공_옵션_줄과_옵션_없는_줄이_섞여도_요청_순서와_상관없이_같은_키_순서로_갱신한다() {
        givenProducts(product("pA", 1000, 0), product("pB", 1000, 5), product("pC", 1000, 0));
        givenOptions(option("o1", "pA", null, "M", 1000, 5), option("o2", "pA", null, "L", 1000, 5),
                option("o3", "pC", null, "M", 1000, 5));
        givenUpdatesSucceed();

        create(new OrderLine("pC", 1, "o3"), new OrderLine("pB", 1), new OrderLine("pA", 1, "o2"),
                new OrderLine("pA", 1, "o1"));

        InOrder inOrder = inOrder(productOptionRepository, productRepository);
        inOrder.verify(productOptionRepository).decrementStockIfAvailable("o1", "pA", 1);
        inOrder.verify(productOptionRepository).decrementStockIfAvailable("o2", "pA", 1);
        inOrder.verify(productRepository).decrementStockIfAvailable("pB", 1);
        inOrder.verify(productOptionRepository).decrementStockIfAvailable("o3", "pC", 1);
    }

    @Test
    void 성공_주문_항목은_갱신과_같은_키_순서로_쌓는다() {
        givenProducts(product("pA", 1000, 5), product("pB", 1000, 0));
        givenOptions(option("o1", "pB", null, "M", 1000, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("pB", 1, "o1"), new OrderLine("pA", 1));

        assertThat(order.getItems()).extracting(OrderItem::stockKey)
                .containsExactly(new StockKey("pA", null), new StockKey("pB", "o1"));
    }

    // ---- option rules (server is final) ----

    @Test
    void 실패_옵션이_있는_상품에_옵션_id가_없으면_거절하고_갱신하지_않는다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", "화이트", "M", 1000, 5));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1)))
                .isInstanceOf(ValidationException.class).hasMessage("옵션을 선택해 주세요.");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_옵션이_없는_상품에_옵션_id가_오면_거절한다() {
        givenProducts(product("p1", 1000, 5));
        givenOptions();

        assertThatThrownBy(() -> create(new OrderLine("p1", 1, "o1")))
                .isInstanceOf(ValidationException.class).hasMessage("선택한 옵션을 찾을 수 없어요.");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    @Test
    void 실패_다른_상품의_옵션_id를_보내면_거절한다() {
        givenProducts(product("p1", 1000, 0), product("p2", 1000, 0));
        givenOptions(option("o1", "p1", null, "M", 1000, 5), option("o2", "p2", null, "M", 1000, 5));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1, "o2")))
                .isInstanceOf(ValidationException.class).hasMessage("선택한 옵션을 찾을 수 없어요.");

        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
    }

    @Test
    void 실패_없는_옵션_id를_보내면_거절한다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", null, "M", 1000, 5));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1, "nope")))
                .isInstanceOf(ValidationException.class).hasMessage("선택한 옵션을 찾을 수 없어요.");
    }

    @Test
    void 실패_여러_상품_중_하나라도_옵션_규칙을_어기면_아무것도_갱신하지_않는다() {
        givenProducts(product("pA", 1000, 5), product("pB", 1000, 0));
        givenOptions(option("o1", "pB", null, "M", 1000, 5));

        assertThatThrownBy(() -> create(new OrderLine("pA", 1), new OrderLine("pB", 1)))
                .isInstanceOf(ValidationException.class).hasMessage("옵션을 선택해 주세요.");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_없는_상품이면_옵션_검사보다_상품_없음이_먼저다() {
        givenProducts(product("p1", 1000, 0));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1), new OrderLine("missing", 1, "o1")))
                .isInstanceOf(ProductNotFoundException.class);

        verifyNoInteractions(productOptionRepository);
    }

    // ---- stock errors name the option ----

    @Test
    void 실패_옵션_재고가_모자라면_옵션_이름을_넣은_재고_부족으로_갱신_없이_거절한다() {
        givenProducts(product("p1", 1000, 100)); // the product stock is irrelevant for a product with options
        givenOptions(option("o1", "p1", "화이트", "M", 1000, 1));

        assertThatThrownBy(() -> create(new OrderLine("p1", 2, "o1")))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("상품 p1").hasMessageContaining("화이트 / M");

        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_옵션_조건부_갱신이_0건이면_옵션_이름을_넣은_재고_부족이고_저장하지_않는다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", "블랙", null, 1000, 5)); // fine when loaded, taken by another order since
        when(productOptionRepository.decrementStockIfAvailable("o1", "p1", 1)).thenReturn(0);

        assertThatThrownBy(() -> create(new OrderLine("p1", 1, "o1")))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("블랙");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_앞선_옵션은_차감됐어도_뒤_옵션이_모자라면_예외로_전체가_롤백된다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", null, "S", 1000, 5), option("o2", "p1", null, "M", 1000, 5));
        when(productOptionRepository.decrementStockIfAvailable("o1", "p1", 1)).thenReturn(1);
        when(productOptionRepository.decrementStockIfAvailable("o2", "p1", 1)).thenReturn(0);

        assertThatThrownBy(() -> create(new OrderLine("p1", 1, "o1"), new OrderLine("p1", 1, "o2")))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("M");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_옵션_금액이_int_범위를_넘으면_검증_오류이고_갱신하지_않는다() {
        givenProducts(product("p1", 1, 0));
        givenOptions(option("o1", "p1", null, "M", 1_000_000_000, 10));

        assertThatThrownBy(() -> create(new OrderLine("p1", 3, "o1")))
                .isInstanceOf(ValidationException.class).hasMessage("주문 금액이 너무 커요.");

        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
    }

    // ---- idempotency ----

    private static String hashOf(Map<StockKey, Integer> lines) {
        return OrderValidation.requestHashByStockKey("구매자", "010-0000-0000", "서울", lines);
    }

    @Test
    void 성공_요청_해시는_옵션_id를_포함해_저장한다() {
        givenProducts(product("p1", 1000, 0));
        givenOptions(option("o1", "p1", null, "M", 1000, 5));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 2, "o1"));

        assertThat(order.getRequestHash()).isEqualTo(hashOf(Map.of(new StockKey("p1", "o1"), 2)));
        assertThat(order.getRequestHash()).isNotEqualTo(hashOf(Map.of(new StockKey("p1", null), 2)));
    }

    private Order existingOrder(String hash) {
        Order existing = new Order(USER, KEY, hash, "구매자", "010-0000-0000", "서울", 1000, 0, NOW);
        ReflectionTestUtils.setField(existing, "id", "order-old");
        when(orderRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));
        return existing;
    }

    @Test
    void 성공_같은_키로_같은_옵션_주문을_다시_보내면_기존_주문을_돌려주고_재고도_저장도_건드리지_않는다() {
        Order existing = existingOrder(hashOf(Map.of(new StockKey("p1", "o1"), 2)));

        Order replay = create(new OrderLine("p1", 1, "o1"), new OrderLine("p1", 1, "o1"));

        assertThat(replay).isSameAs(existing);
        verifyNoInteractions(productRepository, productOptionRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_같은_키로_옵션만_바꾸면_충돌이고_아무것도_바꾸지_않는다() {
        existingOrder(hashOf(Map.of(new StockKey("p1", "o1"), 2)));

        assertThatThrownBy(() -> create(new OrderLine("p1", 2, "o2"))).isInstanceOf(IdempotencyKeyReuseException.class);
        assertThatThrownBy(() -> create(new OrderLine("p1", 2))).isInstanceOf(IdempotencyKeyReuseException.class);

        verifyNoInteractions(productRepository, productOptionRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 성공_기한이_지난_옵션_주문을_같은_키로_다시_보내면_그_자리에서_만료시키고_옵션_재고를_키_순서로_돌려준다() {
        Order existing = new Order(USER, KEY, hashOf(Map.of(new StockKey("p1", "o1"), 1)), "구매자", "010-0000-0000", "서울",
                3000, 0, NOW.minusSeconds(21 * 60));
        ReflectionTestUtils.setField(existing, "id", "order-old");
        // added in reverse key order on purpose: pC legacy, pA/o2, pA/o1
        existing.addItem(new OrderItem(product("pC", 1000, 5), 3, 1000));
        existing.addItem(new OrderItem(product("pA", 1000, 0), 2, 1000, "o2", "블랙 / M"));
        existing.addItem(new OrderItem(product("pA", 1000, 0), 1, 1000, "o1", "화이트 / M"));
        when(orderRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));

        Order replay = create(new OrderLine("p1", 1, "o1"));

        assertThat(replay.getStatus()).isEqualTo(Order.STATUS_EXPIRED);
        InOrder inOrder = inOrder(productOptionRepository, productRepository);
        inOrder.verify(productOptionRepository).incrementStock("o1", 1);
        inOrder.verify(productOptionRepository).incrementStock("o2", 2);
        inOrder.verify(productRepository).incrementStock("pC", 3);
        verify(productRepository, never()).incrementStock("pA", 1);
        verify(productRepository, never()).incrementStock("pA", 2);
        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    // ---- views ----

    @Test
    void 성공_목록의_대표_항목은_옵션_이름표를_함께_돌려준다() {
        Order order = new Order(USER, "k-o1", "h", "구매자", "010", "서울", 1000, 0);
        ReflectionTestUtils.setField(order, "id", "o1");
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-11T00:00:00Z"));
        OrderItem small = new OrderItem(product("p1", 1000, 0), 1, 1000, "x1", "화이트 / S");
        OrderItem big = new OrderItem(product("p2", 3000, 0), 1, 3000, "x2", "블랙 / L");
        ReflectionTestUtils.setField(small, "id", "i1");
        ReflectionTestUtils.setField(big, "id", "i2");
        order.addItem(small);
        order.addItem(big);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 11))).thenReturn(List.of(order));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1"))).thenReturn(List.of(small, big));

        OrderService.OrderSummary summary = service().listMyOrders(USER, 10, null).orders().get(0);

        assertThat(summary.firstItemName()).isEqualTo("상품 p2");
        assertThat(summary.firstItemOptionLabel()).isEqualTo("블랙 / L");
    }

    @Test
    void 성공_옵션_없는_주문의_목록_대표_항목은_이름표가_null이다() {
        Order order = new Order(USER, "k-o1", "h", "구매자", "010", "서울", 1000, 0);
        ReflectionTestUtils.setField(order, "id", "o1");
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-11T00:00:00Z"));
        OrderItem item = new OrderItem(product("p1", 1000, 0), 1, 1000);
        order.addItem(item);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 11))).thenReturn(List.of(order));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1"))).thenReturn(List.of(item));

        assertThat(service().listMyOrders(USER, 10, null).orders().get(0).firstItemOptionLabel()).isNull();
    }
}
