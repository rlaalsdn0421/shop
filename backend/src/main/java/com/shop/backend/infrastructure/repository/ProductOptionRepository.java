package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductOptionRepository extends JpaRepository<ProductOption, String> {

    List<ProductOption> findByProductIdOrderBySortOrderAsc(String productId);

    List<ProductOption> findByProductIdIn(Collection<String> productIds);

    /** Summed option stock of every product that has options (admin list). */
    record StockTotal(String productId, Long total) {
    }

    @Query("SELECT new com.shop.backend.infrastructure.repository.ProductOptionRepository$StockTotal(o.productId, SUM(o.stock)) "
            + "FROM ProductOption o GROUP BY o.productId")
    List<StockTotal> sumStockByProduct();

    /**
     * Option twin of {@link ProductRepository#decrementStockIfAvailable}: atomic check-and-decrement in one statement
     * (the row lock makes concurrent orders for the same option queue up and re-check the WHERE against the committed
     * stock). Returns 0 when the stock is too low or the option does not belong to the product, i.e. nothing changed.
     * Neither flush nor clear on purpose: nothing is pending when it is called, and clearing would detach the
     * products the order items still point to. The loaded ProductOption's in-memory stock is stale afterwards, so the
     * caller must not read or save its stock in the same transaction.
     */
    @Modifying
    @Query("UPDATE ProductOption o SET o.stock = o.stock - :quantity "
            + "WHERE o.id = :id AND o.productId = :productId AND o.stock >= :quantity")
    int decrementStockIfAvailable(@Param("id") String id, @Param("productId") String productId,
                                  @Param("quantity") int quantity);

    /** Gives reserved option stock back (expired/cancelled order, or a re-reservation that had to be rolled back). Same flush/clear caveats as above. */
    @Modifying
    @Query("UPDATE ProductOption o SET o.stock = o.stock + :quantity WHERE o.id = :id")
    int incrementStock(@Param("id") String id, @Param("quantity") int quantity);
}
