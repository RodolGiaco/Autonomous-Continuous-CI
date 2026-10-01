package io.github.rodolgiaco.oms.order.application.port.in;

import io.github.rodolgiaco.oms.order.application.OrderNotCancellableException;
import io.github.rodolgiaco.oms.order.application.OrderNotFoundException;
import io.github.rodolgiaco.oms.order.domain.Order;
import java.util.UUID;

/** Input port for cancelling an existing {@link Order}. */
public interface CancelOrderUseCase {

  /**
   * Cancels the order with the given identifier and stores it.
   *
   * @param orderId the identifier of the order, never null
   * @return the order as it was stored, in status {@code CANCELLED}
   * @throws OrderNotFoundException if no order has that identifier
   * @throws OrderNotCancellableException if the order is not in a status that allows a cancellation
   */
  Order cancelOrder(UUID orderId);
}
