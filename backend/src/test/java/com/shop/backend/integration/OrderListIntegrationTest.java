package com.shop.backend.integration;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.OrderService.OrderPage;
import com.shop.backend.application.service.OrderService.OrderSummary;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderCursor;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ValidationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "My orders" keyset pagination and V11 on a real Postgres: the three JPQL queries, the (created_at, id) tiebreak,
 * microsecond round-trips through the cursor, owner isolation, the in-memory aggregates and the N+1 guard.
 */
class OrderListIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private OrderService orderService;

    /** productId, quantity, unit price, explicit order_items.id (null = generated). */
    private record Line(String productId, int quantity, int price, String itemId) {
    }

    private void product(String id) {
        data.product(id, null, 1000, null, TestData.T0);
    }

    private void order(String id, String userId, Instant createdAt, Line... lines) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Order order = new Order(userId, null, null, "구매자", "010-0000-0000", "서울", 1000, 0);
            ReflectionTestUtils.setField(order, "id", id);
            ReflectionTestUtils.setField(order, "createdAt", createdAt);
            for (Line line : lines) {
                OrderItem item = new OrderItem(em.getReference(Product.class, line.productId()), line.quantity(), line.price());
                if (line.itemId() != null) {
                    ReflectionTestUtils.setField(item, "id", line.itemId());
                }
                order.addItem(item);
            }
            em.persist(order);
        });
    }

    private void order(String id, String userId, Instant createdAt) {
        order(id, userId, createdAt, new Line("p1", 1, 1000, null));
    }

    /** Follows nextCursor to the end; returns the order ids in the order they were served. */
    private List<String> pageThrough(String userId, int size, List<Integer> pageSizes) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            OrderPage page = orderService.listMyOrders(userId, size, cursor);
            pageSizes.add(page.orders().size());
            page.orders().forEach(o -> ids.add(o.order().getId()));
            cursor = page.nextCursor();
            assertThat(++guard).as("cursor must advance, never loop").isLessThan(200);
        } while (cursor != null);
        return ids;
    }

    private List<String> ids(OrderPage page) {
        return page.orders().stream().map(o -> o.order().getId()).toList();
    }

    // 1. migration / schema

    @Test
    void 성공_V11_후_새_복합_인덱스가_있고_옛_인덱스는_없다() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND schemaname = current_schema() "
                        + "AND indexname = 'idx_orders_user_created_id'", String.class);
        Integer old = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'idx_orders_user_created'",
                Integer.class);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0)).contains("(user_id, created_at DESC, id DESC)");
        assertThat(old).isZero();
    }

    @Test
    void 성공_플래너가_seq_scan을_못_쓰면_첫_페이지와_키셋_쿼리가_새_인덱스를_쓴다() throws Exception {
        String userId = data.user();
        product("p1");
        order("ord01", userId, T);
        // enable_seqscan=off only proves the index is *usable* for both predicates (few rows -> the planner would
        // otherwise pick a seq scan); it says nothing about the production plan.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET enable_seqscan = off");
            String first = "EXPLAIN SELECT id FROM orders WHERE user_id = '" + userId
                    + "' ORDER BY created_at DESC, id DESC LIMIT 11";
            String after = "EXPLAIN SELECT id FROM orders WHERE user_id = '" + userId
                    + "' AND (created_at, id) < (TIMESTAMP '2026-03-01 10:00:00', 'x')"
                    + " ORDER BY created_at DESC, id DESC LIMIT 11";
            String firstPlan = plan(st, first);
            String afterPlan = plan(st, after);
            assertThat(firstPlan).contains("idx_orders_user_created_id");
            assertThat(afterPlan).contains("idx_orders_user_created_id");
            // the index already delivers (created_at DESC, id DESC) order, so no Sort; the row comparison is an index condition
            assertThat(firstPlan).doesNotContain("Sort");
            assertThat(afterPlan).doesNotContain("Sort");
            assertThat(afterPlan).containsPattern("Index Cond:.*ROW\\(created_at, id\\) <");
        }
    }

    private static String plan(Statement st, String explain) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery(explain)) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('\n');
            }
        }
        return sb.toString();
    }

    // 2. first page

    @Test
    void 성공_첫_페이지는_본인_주문만_최신순으로_size개와_nextCursor를_준다() {
        String me = data.user();
        product("p1");
        for (int i = 0; i < 5; i++) {
            order("ord0" + i, me, T.plusSeconds(i));
        }

        OrderPage page = orderService.listMyOrders(me, 3, null);

        assertThat(ids(page)).containsExactly("ord04", "ord03", "ord02");
        assertThat(page.nextCursor()).isNotNull();
        OrderCursor next = OrderCursor.decode(page.nextCursor());
        assertThat(next.id()).isEqualTo("ord02");
        assertThat(next.createdAt()).isEqualTo(T.plusSeconds(2));
    }

    @Test
    void 성공_정확히_size개면_nextCursor가_없고_size_더하기_1개면_있다() {
        String me = data.user();
        product("p1");
        for (int i = 0; i < 50; i++) {
            order(String.format("ord%02d", i), me, T.plusSeconds(i));
        }

        OrderPage exact = orderService.listMyOrders(me, 50, null);
        assertThat(exact.orders()).hasSize(50);
        assertThat(exact.nextCursor()).isNull();

        order("ord99", me, T.plusSeconds(99));
        OrderPage over = orderService.listMyOrders(me, 50, null);
        assertThat(over.orders()).hasSize(50);
        assertThat(over.nextCursor()).isNotNull();
        assertThat(ids(orderService.listMyOrders(me, 50, over.nextCursor()))).containsExactly("ord00");
    }

    // 3. keyset continuation

    @Test
    void 성공_25건을_size_10으로_3페이지에_중복과_누락_없이_모두_받고_마지막_cursor는_null이다() {
        String me = data.user();
        product("p1");
        for (int i = 0; i < 25; i++) {
            order("ord" + String.format("%02d", i), me, T.plusSeconds(i * 7L));
        }

        List<Integer> sizes = new ArrayList<>();
        List<String> served = pageThrough(me, 10, sizes);

        assertThat(sizes).containsExactly(10, 10, 5);
        assertThat(served).hasSize(25).doesNotHaveDuplicates();
        List<String> expected = new ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            expected.add("ord" + String.format("%02d", i));
        }
        assertThat(served).containsExactlyElementsOf(expected);
        assertThat(orderService.listMyOrders(me, 10, orderService.listMyOrders(me, 10, null).nextCursor()).nextCursor())
                .isNotNull(); // page 2 of 3 still has a next
    }

    @Test
    void 성공_size_1로_한_건씩_넘겨도_전부_한_번씩_나온다() {
        String me = data.user();
        product("p1");
        for (int i = 0; i < 4; i++) {
            order("ord0" + i, me, T.plusSeconds(i));
        }

        List<Integer> sizes = new ArrayList<>();
        assertThat(pageThrough(me, 1, sizes)).containsExactly("ord03", "ord02", "ord01", "ord00");
        assertThat(sizes).containsExactly(1, 1, 1, 1);
    }

    // 4. same created_at tiebreak

    @Test
    void 성공_같은_created_at_주문이_페이지_경계에_걸려도_중복과_누락이_없다() {
        String me = data.user();
        product("p1");
        // 7 orders in the same instant + 2 older + 1 newer; size 3 puts the boundary inside the tie group twice
        for (int i = 0; i < 7; i++) {
            order("tie0" + i, me, T);
        }
        order("old00", me, T.minusSeconds(10));
        order("old01", me, T.minusSeconds(10));
        order("new00", me, T.plusSeconds(10));

        List<Integer> sizes = new ArrayList<>();
        List<String> served = pageThrough(me, 3, sizes);

        assertThat(served).containsExactly(
                "new00", "tie06", "tie05", "tie04", "tie03", "tie02", "tie01", "tie00", "old01", "old00");
        assertThat(sizes).containsExactly(3, 3, 3, 1);
    }

    // 5. microseconds round trip

    @Test
    void 성공_마이크로초_created_at이_cursor를_왕복해도_건너뛰거나_반복되지_않는다() {
        String me = data.user();
        product("p1");
        Instant micro = Instant.parse("2026-03-01T10:00:00.123457Z");
        // ord02/ord01/ord00 share the same microsecond timestamp; the boundary of size 2 sits inside that group
        order("ord03", me, micro.plus(1, ChronoUnit.MICROS));
        order("ord02", me, micro);
        order("ord01", me, micro);
        order("ord00", me, micro);
        order("ord_1", me, micro.minus(1, ChronoUnit.MICROS));

        List<Integer> sizes = new ArrayList<>();
        List<String> served = pageThrough(me, 2, sizes);

        assertThat(served).containsExactly("ord03", "ord02", "ord01", "ord00", "ord_1");
        assertThat(sizes).containsExactly(2, 2, 1);
    }

    @Test
    void 성공_나노초가_있는_Instant로_저장된_주문도_cursor_왕복에서_건너뛰지_않는다() {
        String me = data.user();
        product("p1");
        // Postgres keeps microseconds; the cursor must use the value read back, not the in-memory nanos
        Instant nanos = Instant.parse("2026-03-01T10:00:00.123456789Z");
        order("ord02", me, nanos);
        order("ord01", me, nanos);
        order("ord00", me, nanos);

        List<Integer> sizes = new ArrayList<>();
        assertThat(pageThrough(me, 1, sizes)).containsExactly("ord02", "ord01", "ord00");
    }

    // 6. isolation

    @Test
    void 성공_다른_사용자와_user_id_NULL_옛_주문은_절대_안_나온다() {
        String me = data.user();
        String other = data.user();
        product("p1");
        order("mine1", me, T);
        order("mine2", me, T.plusSeconds(1));
        order("other1", other, T.plusSeconds(2));
        order("legacy1", null, T.plusSeconds(3));
        order("legacy2", null, T.plusSeconds(4));

        assertThat(pageThrough(me, 50, new ArrayList<>())).containsExactly("mine2", "mine1");
        assertThat(pageThrough(other, 50, new ArrayList<>())).containsExactly("other1");
        // the keyset query must stay scoped to the user even when the cursor points at another user's order
        String foreignCursor = new OrderCursor(T.plusSeconds(10), "zzz").encode();
        assertThat(ids(orderService.listMyOrders(me, 50, foreignCursor))).containsExactly("mine2", "mine1");
    }

    @Test
    void 실패_null이나_빈_userId는_옛_NULL_주문을_포함해_아무것도_돌려주지_않는다() {
        product("p1");
        order("legacy1", null, T);

        assertThat(orderService.listMyOrders(null, 10, null).orders()).isEmpty();
        assertThat(orderService.listMyOrders("  ", 10, null).orders()).isEmpty();
        assertThat(orderService.listMyOrders(null, 10, null).nextCursor()).isNull();
    }

    // 7. aggregates

    @Test
    void 성공_itemCount는_라인_수이고_firstItemName은_라인_금액이_가장_큰_상품이다() {
        String me = data.user();
        product("pA");
        product("pB");
        product("pC");
        // pA 1000x1 = 1000, pB 500x3 = 1500 (largest), pC 100x2 = 200
        order("ord01", me, T,
                new Line("pA", 1, 1000, null), new Line("pB", 3, 500, null), new Line("pC", 2, 100, null));

        OrderSummary summary = orderService.listMyOrders(me, 10, null).orders().get(0);

        assertThat(summary.itemCount()).isEqualTo(3);
        assertThat(summary.firstItemName()).isEqualTo("상품 pB");
    }

    @Test
    void 성공_라인_금액이_같으면_order_items_id가_작은_라인의_상품이_대표다() {
        String me = data.user();
        product("pA");
        product("pB");
        // both 2000; the line with the larger id is inserted first so insertion order cannot decide
        order("ord01", me, T, new Line("pA", 2, 1000, "item-b"), new Line("pB", 1, 2000, "item-a"));

        OrderSummary summary = orderService.listMyOrders(me, 10, null).orders().get(0);

        assertThat(summary.itemCount()).isEqualTo(2);
        assertThat(summary.firstItemName()).isEqualTo("상품 pB");
    }

    @Test
    void 성공_라인이_하나인_주문과_라인_없는_주문도_목록에_나온다() {
        String me = data.user();
        product("p1");
        order("ord01", me, T, new Line("p1", 1, 1000, null));
        order("ord02", me, T.plusSeconds(1)); // the 3-arg helper gives this order one default line; ord03 below has none
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Order empty = new Order(me, null, null, "구매자", "010-0000-0000", "서울", 0, 0);
            ReflectionTestUtils.setField(empty, "id", "ord03");
            ReflectionTestUtils.setField(empty, "createdAt", T.plusSeconds(2));
            em.persist(empty);
        });

        List<OrderSummary> orders = orderService.listMyOrders(me, 10, null).orders();

        assertThat(orders).extracting(o -> o.order().getId()).containsExactly("ord03", "ord02", "ord01");
        assertThat(orders.get(0).itemCount()).isZero();
        assertThat(orders.get(0).firstItemName()).isNull();
        assertThat(orders.get(1).itemCount()).isEqualTo(1);
        assertThat(orders.get(1).firstItemName()).isEqualTo("상품 p1");
    }

    // 8. N+1

    @Test
    void 성공_주문_25건_한_페이지도_SQL_실행_횟수가_주문_수에_비례하지_않는다() {
        String me = data.user();
        String few = data.user();
        product("pA");
        product("pB");
        for (int i = 0; i < 25; i++) {
            order(String.format("ord%02d", i), me, T.plusSeconds(i), new Line("pA", 1, 1000, null), new Line("pB", 2, 500, null));
        }
        for (int i = 0; i < 2; i++) {
            order(String.format("few%02d", i), few, T.plusSeconds(i), new Line("pA", 1, 1000, null), new Line("pB", 2, 500, null));
        }
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            OrderPage fewPage = orderService.listMyOrders(few, 50, null);
            long fewStatements = stats.getPrepareStatementCount();

            stats.clear();
            OrderPage bigPage = orderService.listMyOrders(me, 50, null);
            long bigStatements = stats.getPrepareStatementCount();

            assertThat(fewPage.orders()).hasSize(2);
            assertThat(bigPage.orders()).hasSize(25);
            assertThat(bigPage.orders()).allSatisfy(o -> assertThat(o.itemCount()).isEqualTo(2));
            assertThat(bigStatements).as("25 orders must not cost more statements than 2 orders").isEqualTo(fewStatements);
            assertThat(bigStatements).as("orders query + items query").isLessThanOrEqualTo(3);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    // 9. empty / bounds / bad cursor

    @Test
    void 성공_주문이_없는_사용자는_빈_목록과_null_cursor를_받는다() {
        String me = data.user();

        OrderPage page = orderService.listMyOrders(me, 10, null);

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 성공_마지막_주문_뒤_cursor는_빈_목록이다() {
        String me = data.user();
        product("p1");
        order("ord01", me, T);

        OrderPage page = orderService.listMyOrders(me, 10, new OrderCursor(T, "ord01").encode());

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 실패_size가_범위_밖이면_ValidationException() {
        String me = data.user();

        assertThatThrownBy(() -> orderService.listMyOrders(me, 0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> orderService.listMyOrders(me, 51, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> orderService.listMyOrders(me, -1, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_잘못된_cursor는_쿼리까지_가지_않고_ValidationException() {
        String me = data.user();
        String noBar = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-03-01T10:00:00Z".getBytes());
        String badTime = Base64.getUrlEncoder().withoutPadding().encodeToString("not-a-time|ord01".getBytes());
        String tooLongId = Base64.getUrlEncoder().withoutPadding().encodeToString(("2026-03-01T10:00:00Z|" + "x".repeat(37)).getBytes());

        for (String bad : List.of("", "!!!", "%%%", noBar, badTime, tooLongId, "a".repeat(201))) {
            assertThatThrownBy(() -> orderService.listMyOrders(me, 10, bad))
                    .as("cursor <%s>", bad).isInstanceOf(ValidationException.class);
        }
    }
}
