package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    private OrderRepository orderRepository;
    private static final String USER = "user-1";
    private static final String KEY = "key-12345";
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private static Product product(String id, int price, int stock) {
        Product product = new Product("상품 " + id, "설명", price, "http://img/" + id, stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private Order create(OrderLine... lines) {
        return new OrderService(productRepository, orderRepository, em)
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

        assertThat(order.getTotalAmount()).isEqualTo(2 * 1500 + 3 * 700);
        assertThat(order.getItems()).extracting(i -> i.getPrice()).containsExactlyInAnyOrder(1500, 700);
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
        Order existing = new Order(USER, KEY, hashOfLines, HASH_SOURCE_NAME, "010-0000-0000", "서울", 1000);
        ReflectionTestUtils.setField(existing, "id", "order-old");
        when(orderRepository.findByUserIdAndIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing));
        return existing;
    }

    private static String hashOf(String productId, int quantity) {
        return OrderValidation.requestHash("구매자", "010-0000-0000", "서울", Map.of(productId, quantity));
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
        OrderService service = new OrderService(productRepository, orderRepository, em);

        assertThatThrownBy(() -> service.createOrder(USER, null, "구매자", "010", "서울", List.of(new OrderLine("p1", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("요청 번호(Idempotency-Key)가 올바르지 않아요.");

        verifyNoInteractions(em, productRepository, orderRepository);
    }

    @Test
    void 성공_주문은_주문자_본인_기준으로_조회하고_항목과_상품명을_미리_읽는다() {
        Order order = new Order(USER, KEY, "h", "구매자", "010", "서울", 1000);
        OrderItem item = new OrderItem(product("p1", 1000, 10), 1, 1000);
        order.addItem(item);
        when(orderRepository.findByIdAndUserId("o1", USER)).thenReturn(Optional.of(order));

        assertThat(new OrderService(productRepository, orderRepository, em).getOrder("o1", USER)).containsSame(order);
    }

    @Test
    void 실패_남의_주문이거나_없거나_주문자_없는_옛_주문이면_빈_결과다() {
        when(orderRepository.findByIdAndUserId("o1", "someone-else")).thenReturn(Optional.empty());

        assertThat(new OrderService(productRepository, orderRepository, em).getOrder("o1", "someone-else")).isEmpty();
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void 실패_주문자가_null이거나_비어_있으면_조회하지_않고_빈_결과다() {
        OrderService service = new OrderService(productRepository, orderRepository, em);

        assertThat(service.getOrder("o1", null)).isEmpty();
        assertThat(service.getOrder("o1", "")).isEmpty();
        assertThat(service.getOrder("o1", "  ")).isEmpty();

        verifyNoInteractions(orderRepository);
    }
}
