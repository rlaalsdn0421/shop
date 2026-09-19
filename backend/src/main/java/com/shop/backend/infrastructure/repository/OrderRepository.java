package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {
}
