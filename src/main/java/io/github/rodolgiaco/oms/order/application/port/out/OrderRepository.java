package io.github.rodolgiaco.oms.order.application.port.out;

import io.github.rodolgiaco.oms.order.domain.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port through which the application stores and loads {@link Order} aggregates. */
public interface OrderRepository {

  /**
   * Stores an order together with all its items, replacing any order stored under the same
   * identifier.
   *
   * @param order the order to store, never null
   */
  void save(Order order);

  /**
   * Loads the order with the given identifier together with all its items.
   *
   * @param id the identifier of the order, never null
   * @return the order, or empty when no order has that identifier
   */
  Optional<Order> findById(UUID id);

  /**
   * Loads every stored order together with all its items.
   *
   * @return every stored order once, in no specified order, or an empty list when none is stored
   */
  List<Order> findAll();
}
