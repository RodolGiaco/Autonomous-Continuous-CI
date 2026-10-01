package io.github.rodolgiaco.oms.order.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@link OrderEntity}, used only by {@link JpaOrderRepositoryAdapter}. */
interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

  // The items are lazy, so mapping every order would read them with one query
  // per order. The entity graph fetches them in the same query as the orders.
  @Override
  @EntityGraph(attributePaths = "items")
  List<OrderEntity> findAll();
}
