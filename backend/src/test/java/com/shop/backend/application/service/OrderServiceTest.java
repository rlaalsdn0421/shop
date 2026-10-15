package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderCursor;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ShippingPolicy;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Plain Mockito: orchestration only. The atomicity against a real database is covered by the integration tests. */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

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

    private Order create(OrderLine... lines) {
        return new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK)
                .createOrder(USER, KEY, "구매자", "010-0000-0000", "서울", List.of(lines));
    }

    private void givenProducts(Product... products) {
        when(productRepository.findAllByIdIn(anyList())).thenReturn(List.of(products));
    }

    private void givenUpdatesSucceed() {
        when(productRepository.decrementStockIfAvailable(anyString(), anyInt())).thenReturn(1);
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());
    }

    @Test
    void 성공_가격은_조회한_상품에서_가져와_총액과_항목에_저장한다() {
        givenProducts(product("p1", 1500, 10), product("p2", 700, 10));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p2", 3), new OrderLine("p1", 2));

        int itemsAmount = 2 * 1500 + 3 * 700; // 5,100 is under the free-shipping threshold
        assertThat(order.getShippingFee()).isEqualTo(ShippingPolicy.FEE);
        assertThat(order.getTotalAmount()).isEqualTo(itemsAmount + ShippingPolicy.FEE);
        assertThat(order.getItems()).extracting(i -> i.getPrice()).containsExactlyInAnyOrder(1500, 700);
    }

    @Test
    void 성공_새_주문은_결제_대기로_시작하고_20분_뒤에_만료된다() {
        givenProducts(product("p1", 1000, 10));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1));

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plusSeconds(20 * 60));
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    void 성공_상품은_요청_순서와_상관없이_id_순서로_갱신한다() {
        givenProducts(product("pA", 1000, 10), product("pB", 1000, 10));
        givenUpdatesSucceed();

        create(new OrderLine("pB", 1), new OrderLine("pA", 1));

        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).decrementStockIfAvailable("pA", 1);
        inOrder.verify(productRepository).decrementStockIfAvailable("pB", 1);
    }

    @Test
    void 성공_갱신_전에_행_잠금_대기_시간_제한을_건다() {
        givenProducts(product("p1", 1000, 10));
        givenUpdatesSucceed();

        create(new OrderLine("p1", 1));

        InOrder inOrder = inOrder(em, productRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(productRepository).decrementStockIfAvailable("p1", 1);
    }

    @Test
    void 실패_입력이_잘못되면_DB에_아무것도_하지_않는다() {
        assertThatThrownBy(() -> create(new OrderLine(null, 1))).isInstanceOf(ValidationException.class);

        verifyNoInteractions(em, productRepository, orderRepository);
    }

    @Test
    void 성공_유효한_큰_수량과_금액은_그대로_계산한다() {
        givenProducts(product("p1", 2, Integer.MAX_VALUE));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 500_000_000), new OrderLine("p1", 500_000_000));

        assertThat(order.getTotalAmount()).isEqualTo(2_000_000_000);
        assertThat(order.getShippingFee()).isZero();
    }

    @Test
    void 성공_상품_금액이_5만원_미만이면_배송비를_총액에_더한다() {
        givenProducts(product("p1", 49_999, 10));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1));

        assertThat(order.getShippingFee()).isEqualTo(3_500);
        assertThat(order.getTotalAmount()).isEqualTo(53_499);
    }

    @Test
    void 성공_상품_금액이_정확히_5만원이면_배송비가_무료다() {
        givenProducts(product("p1", 50_000, 10));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 1));

        assertThat(order.getShippingFee()).isZero();
        assertThat(order.getTotalAmount()).isEqualTo(50_000);
    }

    @Test
    void 성공_여러_상품_합계가_49999이면_배송비를_받고_50000이면_받지_않는다() {
        givenProducts(product("p1", 30_000, 10), product("p2", 19_999, 10), product("p3", 20_000, 10));
        givenUpdatesSucceed();

        Order under = create(new OrderLine("p1", 1), new OrderLine("p2", 1));
        Order atThreshold = create(new OrderLine("p1", 1), new OrderLine("p3", 1));

        assertThat(under.getShippingFee()).isEqualTo(3_500);
        assertThat(under.getTotalAmount()).isEqualTo(53_499);
        assertThat(atThreshold.getShippingFee()).isZero();
        assertThat(atThreshold.getTotalAmount()).isEqualTo(50_000);
    }

    @Test
    void 성공_배송비는_수량을_곱한_합계로_판단한다() {
        givenProducts(product("p1", 25_000, 10));
        givenUpdatesSucceed();

        assertThat(create(new OrderLine("p1", 1)).getShippingFee()).isEqualTo(3_500);
        assertThat(create(new OrderLine("p1", 2)).getShippingFee()).isZero();
    }

    @Test
    void 실패_상품_금액이_int_최대값이면_그대로_계산되고_하나라도_더_넘으면_검증_오류다() {
        // The fee is 0 at that size, so adding it can never overflow: only the items sum can.
        givenProducts(product("p1", Integer.MAX_VALUE, 10));
        givenUpdatesSucceed();

        assertThat(create(new OrderLine("p1", 1)).getTotalAmount()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> create(new OrderLine("p1", 2)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("주문 금액이 너무 커요.");
    }

    @Test
    void 실패_상품_하나의_금액이_int_범위를_넘으면_검증_오류이고_갱신하지_않는다() {
        givenProducts(product("p1", 100_000_000, 1000));

        assertThatThrownBy(() -> create(new OrderLine("p1", 100)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("주문 금액이 너무 커요.");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    @Test
    void 실패_주문_총액이_int_범위를_넘으면_검증_오류이고_갱신하지_않는다() {
        givenProducts(product("p1", 1_000_000_000, 10), product("p2", 1_000_000_000, 10));

        assertThatThrownBy(() -> create(new OrderLine("p1", 2), new OrderLine("p2", 2)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("주문 금액이 너무 커요.");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    @Test
    void 실패_이미_재고가_모자란_상품은_갱신_없이_재고_부족으로_거절한다() {
        givenProducts(product("p1", 1000, 0));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("상품 p1");

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_조건부_갱신이_0건이면_재고_부족이고_주문을_저장하지_않는다() {
        givenProducts(product("p1", 1000, 5)); // looked fine when loaded, but another order took the stock since
        when(productRepository.decrementStockIfAvailable("p1", 1)).thenReturn(0);

        assertThatThrownBy(() -> create(new OrderLine("p1", 1))).isInstanceOf(InsufficientStockException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_없는_상품이_있으면_품절_상품이_섞여도_상품_없음이_우선한다() {
        givenProducts(product("p1", 1000, 0));

        assertThatThrownBy(() -> create(new OrderLine("p1", 1), new OrderLine("missing", 1)))
                .isInstanceOf(ProductNotFoundException.class);

        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
    }

    private static final String HASH_SOURCE_NAME = "구매자";

    private Order existingOrder(String hashOfLines) {
        Order existing = new Order(USER, KEY, hashOfLines, HASH_SOURCE_NAME, "010-0000-0000", "서울", 1000, 0);
        ReflectionTestUtils.setField(existing, "id", "order-old");
        when(orderRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));
        return existing;
    }

    private static String hashOf(String productId, int quantity) {
        return OrderValidation.requestHash("구매자", "010-0000-0000", "서울", Map.of(productId, quantity));
    }

    /** A replayable order with lines pB then pA (2 units each), created at {@code created}, status as given. */
    private Order replayOrder(String status, Instant created) {
        Order existing = new Order(USER, KEY, hashOf("p1", 1), HASH_SOURCE_NAME, "010-0000-0000", "서울", 2000, 0, created);
        ReflectionTestUtils.setField(existing, "id", "order-old");
        ReflectionTestUtils.setField(existing, "status", status);
        existing.addItem(new OrderItem(product("pB", 1000, 5), 2, 1000));
        existing.addItem(new OrderItem(product("pA", 1000, 5), 2, 1000));
        when(orderRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));
        return existing;
    }

    @Test
    void 성공_기한이_지난_대기_주문을_같은_키로_다시_보내면_그_자리에서_만료시키고_재고를_상품_id_순으로_돌려준다() {
        Order existing = replayOrder(Order.STATUS_PENDING_PAYMENT, NOW.minusSeconds(21 * 60));

        Order replay = create(new OrderLine("p1", 1));

        assertThat(replay).isSameAs(existing);
        assertThat(replay.getStatus()).isEqualTo(Order.STATUS_EXPIRED);
        InOrder inOrder = inOrder(em, productRepository);
        inOrder.verify(em).lock(existing, LockModeType.PESSIMISTIC_WRITE); // lock the row, THEN re-read it, then the products
        inOrder.verify(em).refresh(existing);
        inOrder.verify(productRepository).incrementStock("pA", 2);
        inOrder.verify(productRepository).incrementStock("pB", 2);
        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void 성공_기한_전이거나_이미_결제_만료된_주문의_재생은_아무것도_바꾸지_않는다() {
        Order fresh = replayOrder(Order.STATUS_PENDING_PAYMENT, NOW.minusSeconds(10 * 60));
        assertThat(create(new OrderLine("p1", 1)).getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);

        for (String status : List.of(Order.STATUS_PAID, Order.STATUS_EXPIRED, Order.STATUS_PAYMENT_REVIEW)) {
            Order overdue = replayOrder(status, NOW.minusSeconds(60 * 60));
            assertThat(create(new OrderLine("p1", 1))).isSameAs(overdue);
            assertThat(overdue.getStatus()).isEqualTo(status);
        }

        assertThat(fresh.getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);
        verify(em, never()).lock(any(Object.class), any(LockModeType.class));
        verify(em, never()).refresh(any(Object.class));
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_정확히_기한_시각의_재생은_아직_만료가_아니다() {
        replayOrder(Order.STATUS_PENDING_PAYMENT, NOW.minusSeconds(20 * 60)); // expiresAt == now

        assertThat(create(new OrderLine("p1", 1)).getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);

        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_주문자와_요청_번호와_해시를_주문에_저장한다() {
        givenProducts(product("p1", 1000, 10));
        givenUpdatesSucceed();

        Order order = create(new OrderLine("p1", 2));

        assertThat(order.getUserId()).isEqualTo(USER);
        assertThat(order.getIdempotencyKey()).isEqualTo(KEY);
        assertThat(order.getRequestHash()).isEqualTo(hashOf("p1", 2));
    }

    @Test
    void 성공_같은_키로_같은_내용이면_기존_주문을_돌려주고_재고도_저장도_건드리지_않는다() {
        Order existing = existingOrder(hashOf("p1", 2));

        Order order = create(new OrderLine("p1", 1), new OrderLine("p1", 1)); // same content, split differently

        assertThat(order).isSameAs(existing);
        assertThat(order.getTotalAmount()).isEqualTo(1000); // replay returns the stored order: fee is not recomputed
        assertThat(order.getShippingFee()).isZero();
        verifyNoInteractions(productRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 실패_같은_키로_내용이_다르면_충돌이고_아무것도_바꾸지_않는다() {
        existingOrder(hashOf("p1", 2));

        assertThatThrownBy(() -> create(new OrderLine("p1", 3))).isInstanceOf(IdempotencyKeyReuseException.class);

        verifyNoInteractions(productRepository);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 성공_같은_키_요청을_직렬화하는_잠금을_잠금_대기_제한_뒤_기존_주문_조회_앞에서_건다() {
        givenProducts(product("p1", 1000, 10));
        givenUpdatesSucceed();

        create(new OrderLine("p1", 1));

        InOrder inOrder = inOrder(em, orderRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(orderRepository).lockUserIdempotencyKey(USER, KEY);
        inOrder.verify(orderRepository).findByUserIdAndIdempotencyKey(USER, KEY);
    }

    @Test
    void 실패_요청_번호가_올바르지_않으면_DB에_아무것도_하지_않는다() {
        OrderService service = new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK);

        assertThatThrownBy(() -> service.createOrder(USER, null, "구매자", "010", "서울", List.of(new OrderLine("p1", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("요청 번호(Idempotency-Key)가 올바르지 않아요.");

        verifyNoInteractions(em, productRepository, orderRepository);
    }

    @Test
    void 성공_주문은_주문자_본인_기준으로_조회하고_항목과_상품명을_미리_읽는다() {
        Order order = new Order(USER, KEY, "h", "구매자", "010", "서울", 1000, 0);
        OrderItem item = new OrderItem(product("p1", 1000, 10), 1, 1000);
        order.addItem(item);
        when(orderRepository.findByIdAndUserId("o1", USER)).thenReturn(Optional.of(order));

        assertThat(new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK).getOrder("o1", USER)).containsSame(order);
    }

    @Test
    void 실패_남의_주문이거나_없거나_주문자_없는_옛_주문이면_빈_결과다() {
        when(orderRepository.findByIdAndUserId("o1", "someone-else")).thenReturn(Optional.empty());

        assertThat(new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK).getOrder("o1", "someone-else")).isEmpty();
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void 실패_주문자가_null이거나_비어_있으면_조회하지_않고_빈_결과다() {
        OrderService service = new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK);

        assertThat(service.getOrder("o1", null)).isEmpty();
        assertThat(service.getOrder("o1", "")).isEmpty();
        assertThat(service.getOrder("o1", "  ")).isEmpty();

        verifyNoInteractions(orderRepository);
    }

    private static Order listedOrder(String id, Instant createdAt) {
        Order order = new Order(USER, "k-" + id, "h", "구매자", "010", "서울", 1000, 0);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }

    private static OrderItem line(Order order, String itemId, String productName, int price, int quantity) {
        Product product = new Product(productName, "설명", price, "http://img/x", 5);
        OrderItem item = new OrderItem(product, quantity, price);
        ReflectionTestUtils.setField(item, "id", itemId);
        order.addItem(item);
        return item;
    }

    private OrderService service() {
        return new OrderService(productRepository, productOptionRepository, orderRepository, em, CLOCK);
    }

    @Test
    void 성공_첫_페이지는_size_더하기_1개를_조회하고_다음_페이지가_있으면_마지막_주문으로_커서를_만든다() {
        Instant t = Instant.parse("2026-10-11T01:02:03.123456Z");
        Order o1 = listedOrder("o3", t.plusSeconds(2));
        Order o2 = listedOrder("o2", t.plusSeconds(1));
        Order o3 = listedOrder("o1", t);
        line(o1, "i1", "A", 1000, 1);
        line(o2, "i2", "B", 1000, 1);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 3))).thenReturn(List.of(o1, o2, o3));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o3", "o2")))
                .thenReturn(List.of(o1.getItems().get(0), o2.getItems().get(0)));

        OrderService.OrderPage page = service().listMyOrders(USER, 2, null);

        assertThat(page.orders()).extracting(s -> s.order().getId()).containsExactly("o3", "o2");
        OrderCursor next = OrderCursor.decode(page.nextCursor());
        assertThat(next).isEqualTo(new OrderCursor(t.plusSeconds(1), "o2"));
        verify(orderRepository, never()).findPageByUserAfter(any(), any(), any(), any());
    }

    @Test
    void 성공_마지막_페이지는_nextCursor가_null이다() {
        Order o1 = listedOrder("o1", Instant.parse("2026-10-11T00:00:00Z"));
        line(o1, "i1", "A", 1000, 1);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 11))).thenReturn(List.of(o1));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1"))).thenReturn(o1.getItems());

        OrderService.OrderPage page = service().listMyOrders(USER, 10, null);

        assertThat(page.orders()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 성공_커서가_있으면_커서_조건_쿼리로_디코딩한_값을_넘긴다() {
        Instant t = Instant.parse("2026-10-11T01:02:03.123456Z");
        String cursor = new OrderCursor(t, "o2").encode();
        when(orderRepository.findPageByUserAfter(USER, t, "o2", PageRequest.of(0, 11))).thenReturn(List.of());

        OrderService.OrderPage page = service().listMyOrders(USER, 10, cursor);

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(orderRepository, never()).findFirstPageByUser(any(), any());
        verify(orderRepository, never()).findItemsWithProductByOrderIds(any());
    }

    @Test
    void 성공_항목수와_대표_상품명은_한_번의_항목_조회로_계산한다() {
        Order o1 = listedOrder("o1", Instant.parse("2026-10-11T00:00:00Z"));
        OrderItem small = line(o1, "i1", "싼것", 1000, 1);
        OrderItem big = line(o1, "i2", "비싼것", 3000, 1);
        Order o2 = listedOrder("o2", Instant.parse("2026-10-10T00:00:00Z"));
        OrderItem only = line(o2, "i3", "하나", 500, 2);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 11))).thenReturn(List.of(o1, o2));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1", "o2"))).thenReturn(List.of(small, big, only));

        List<OrderService.OrderSummary> summaries = service().listMyOrders(USER, 10, null).orders();

        assertThat(summaries).extracting(OrderService.OrderSummary::itemCount).containsExactly(2, 1);
        assertThat(summaries).extracting(OrderService.OrderSummary::firstItemName).containsExactly("비싼것", "하나");
        verify(orderRepository, org.mockito.Mockito.times(1)).findItemsWithProductByOrderIds(anyList());
    }

    @Test
    void 성공_대표_상품은_라인_금액이_큰_순이고_같으면_항목_id_순이다() {
        Order o1 = listedOrder("o1", Instant.parse("2026-10-11T00:00:00Z"));
        // 2000*1 == 1000*2: the tie goes to the smaller line id; quantity counts, not just unit price
        OrderItem later = line(o1, "i9", "나중", 1000, 2);
        OrderItem earlier = line(o1, "i1", "먼저", 2000, 1);
        when(orderRepository.findFirstPageByUser(USER, PageRequest.of(0, 11))).thenReturn(List.of(o1));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1"))).thenReturn(List.of(later, earlier));

        assertThat(service().listMyOrders(USER, 10, null).orders().get(0).firstItemName()).isEqualTo("먼저");
    }

    @Test
    void 실패_size가_범위_밖이면_조회하지_않고_검증_오류다() {
        assertThatThrownBy(() -> service().listMyOrders(USER, 0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service().listMyOrders(USER, 51, null)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(orderRepository);
    }

    @Test
    void 실패_커서가_올바르지_않으면_조회하지_않고_한국어_검증_오류다() {
        assertThatThrownBy(() -> service().listMyOrders(USER, 10, "not-a-cursor!!"))
                .isInstanceOf(ValidationException.class).hasMessage("커서가 올바르지 않아요.");

        verifyNoInteractions(orderRepository);
    }

    @Test
    void 실패_NUL_id나_극단_시각의_커서도_DB_조회_전에_한국어_검증_오류다() {
        java.util.function.Function<String, String> b64 = raw -> java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (String raw : new String[]{"2026-10-11T01:02:03Z|a\u0000b", "+999999999-12-31T23:59:59Z|o1",
                "-1000000000-01-01T00:00:00Z|o1"}) {
            assertThatThrownBy(() -> service().listMyOrders(USER, 10, b64.apply(raw)))
                    .isInstanceOf(ValidationException.class).hasMessage("커서가 올바르지 않아요.");
        }

        verifyNoInteractions(orderRepository);
    }

    @Test
    void 실패_사용자가_null이거나_비어_있으면_조회하지_않고_빈_목록이다() {
        assertThat(service().listMyOrders(null, 10, null).orders()).isEmpty();
        assertThat(service().listMyOrders(" ", 10, null).nextCursor()).isNull();

        verifyNoInteractions(orderRepository);
    }
}
