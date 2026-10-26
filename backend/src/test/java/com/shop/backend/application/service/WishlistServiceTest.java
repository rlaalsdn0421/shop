package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.entity.WishlistItem;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.repository.WishlistRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WishlistServiceTest {

    private final WishlistRepository wishlistRepository = mock(WishlistRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);
    private final WishlistService service = new WishlistService(wishlistRepository, productRepository, userRepository, em);

    {
        // default: u1 is an active account whose row can be locked
        when(userRepository.findForUpdateById("u1")).thenReturn(Optional.of(new User("member01", "m@example.com", "hash", Role.USER)));
    }

    private void productExists(boolean exists) {
        when(productRepository.existsById("p1")).thenReturn(exists);
    }

    private void alreadyWished(boolean wished) {
        when(wishlistRepository.existsById(new WishlistItem.WishlistItemId("u1", "p1"))).thenReturn(wished);
    }

    // ---- add ----

    @Test
    void 성공_새_상품을_찜하면_네이티브_insert를_한_번_호출한다() {
        productExists(true);
        alreadyWished(false);
        when(wishlistRepository.countByUser("u1")).thenReturn(3L);

        service.add("u1", "p1");

        verify(wishlistRepository).insertIfAbsent("u1", "p1");
    }

    @Test
    void 실패_없는_상품이면_ProductNotFoundException이고_아무것도_쓰지_않는다() {
        productExists(false);

        assertThatThrownBy(() -> service.add("u1", "p1"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("p1");

        verify(wishlistRepository, never()).insertIfAbsent(anyString(), anyString());
        verify(wishlistRepository, never()).countByUser(anyString());
    }

    @Test
    void 성공_이미_찜한_상품은_한도에_도달해도_예외_없이_넘어가고_insert하지_않는다() {
        productExists(true);
        alreadyWished(true);
        when(wishlistRepository.countByUser("u1")).thenReturn(200L); // would reject a new one

        service.add("u1", "p1");

        verify(wishlistRepository, never()).insertIfAbsent(anyString(), anyString());
    }

    @Test
    void 실패_한도_200개에_도달한_뒤_새_상품을_찜하면_ValidationException이고_insert하지_않는다() {
        productExists(true);
        alreadyWished(false);
        when(wishlistRepository.countByUser("u1")).thenReturn(200L);

        assertThatThrownBy(() -> service.add("u1", "p1"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("찜은 최대 200개까지 담을 수 있어요.");

        verify(wishlistRepository, never()).insertIfAbsent(anyString(), anyString());
    }

    @Test
    void 경계_199개일_때는_찜할_수_있고_201개_같은_초과_상태도_거절한다() {
        productExists(true);
        alreadyWished(false);

        when(wishlistRepository.countByUser("u1")).thenReturn(199L);
        service.add("u1", "p1");
        verify(wishlistRepository).insertIfAbsent("u1", "p1");

        when(wishlistRepository.countByUser("u1")).thenReturn(201L); // overshoot left by a race
        assertThatThrownBy(() -> service.add("u1", "p1")).isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_개수를_사용자_id로_세고_이미_찜했는지도_그_사용자_기준으로_확인한다() {
        productExists(true);
        alreadyWished(false);
        when(wishlistRepository.countByUser("u1")).thenReturn(0L);

        service.add("u1", "p1");

        verify(wishlistRepository).countByUser("u1");
        verify(wishlistRepository).existsById(new WishlistItem.WishlistItemId("u1", "p1"));
        verify(wishlistRepository, never()).countByUser("other");
    }

    @Test
    void 성공_락_타임아웃을_먼저_걸고_사용자_행을_잠근_뒤에_상품을_확인하고_찜한다() {
        productExists(true);
        alreadyWished(false);
        when(wishlistRepository.countByUser("u1")).thenReturn(0L);

        service.add("u1", "p1");

        InOrder inOrder = inOrder(em, userRepository, productRepository, wishlistRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(userRepository).findForUpdateById("u1");
        inOrder.verify(productRepository).existsById("p1");
        inOrder.verify(wishlistRepository).countByUser("u1");
        inOrder.verify(wishlistRepository).insertIfAbsent("u1", "p1");
    }

    @Test
    void 실패_없는_사용자면_자격_증명_예외이고_상품_조회도_저장도_하지_않는다() {
        when(userRepository.findForUpdateById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add("ghost", "p1")).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(productRepository, wishlistRepository);
    }

    @Test
    void 실패_탈퇴한_사용자면_자격_증명_예외이고_상품_조회도_저장도_하지_않는다() {
        User withdrawn = new User("member02", "m2@example.com", "hash", Role.USER);
        withdrawn.withdraw(java.time.Instant.parse("2026-06-15T00:00:00Z"));
        when(userRepository.findForUpdateById("gone")).thenReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> service.add("gone", "p1")).isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(productRepository, wishlistRepository);
    }

    @Test
    void 실패_상품이_없는_요청도_먼저_사용자_행을_잠근다() {
        productExists(false);

        assertThatThrownBy(() -> service.add("u1", "p1")).isInstanceOf(ProductNotFoundException.class);

        InOrder inOrder = inOrder(userRepository, productRepository);
        inOrder.verify(userRepository).findForUpdateById("u1");
        inOrder.verify(productRepository).existsById("p1");
    }

    @Test
    void 성공_remove는_사용자_행을_잠그지도_락_타임아웃을_걸지도_않는다() {
        service.remove("u1", "p1");

        verifyNoInteractions(userRepository, em);
    }

    // ---- remove ----

    @Test
    void 성공_remove는_사용자와_상품_쌍으로만_삭제를_위임하고_없어도_예외가_없다() {
        when(wishlistRepository.deleteByUserAndProduct("u1", "p1")).thenReturn(0); // nothing to delete

        service.remove("u1", "p1");

        verify(wishlistRepository).deleteByUserAndProduct("u1", "p1");
        verifyNoMoreInteractions(productRepository);
    }

    // ---- ids / list ----

    @Test
    void 성공_ids는_저장소가_준_순서_그대로_200개_상한으로_조회한다() {
        when(wishlistRepository.findProductIdsByUser(anyString(), any(Pageable.class))).thenReturn(List.of("b", "a", "c"));

        List<String> ids = service.ids("u1");

        assertThat(ids).containsExactly("b", "a", "c");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(wishlistRepository).findProductIdsByUser(org.mockito.ArgumentMatchers.eq("u1"), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        assertThat(page.getValue().getSort().isSorted()).as("order lives in the query").isFalse();
    }

    @Test
    void 성공_list는_저장소가_준_순서_그대로_200개_상한으로_조회한다() {
        Product first = new Product("A", "d", 1000, "http://img/a", 1);
        Product second = new Product("B", "d", 1000, "http://img/b", 1);
        when(wishlistRepository.findWishedProductsByUser(anyString(), any(Pageable.class))).thenReturn(List.of(second, first));

        List<Product> products = service.list("u1");

        assertThat(products).containsExactly(second, first);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(wishlistRepository).findWishedProductsByUser(org.mockito.ArgumentMatchers.eq("u1"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(200);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    void 성공_찜이_없으면_빈_목록이다() {
        when(wishlistRepository.findProductIdsByUser(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(wishlistRepository.findWishedProductsByUser(anyString(), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.ids("u1")).isEmpty();
        assertThat(service.list("u1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "no-such-product"})
    void 실패_존재하지_않는_또는_이상한_상품_id는_모두_404_예외다(String productId) {
        when(productRepository.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> service.add("u1", productId)).isInstanceOf(ProductNotFoundException.class);

        verify(wishlistRepository, never()).insertIfAbsent(anyString(), anyString());
    }
}
