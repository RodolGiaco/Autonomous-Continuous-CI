package io.github.rodolgiaco.oms.order.application.port.in;

import io.github.rodolgiaco.oms.order.domain.Order;
import java.util.List;

/** Input port for listing every stored {@link Order}. */
public interface ListOrdersUseCase {

  /**
   * Returns every stored order, whatever its status.
   *
   * @return every stored order once, in no specified order, or an empty list when none is stored
   */
  List<Order> listOrders();
}
