package io.github.rodolgiaco.oms.order.application;

import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.util.UUID;

/** Thrown when an order is asked to be cancelled while it is in a status that does not allow it. */
public class OrderNotCancellableException extends RuntimeException {

  private final UUID orderId;

  private final OrderStatus status;

  /**
   * Creates the exception from the domain's refusal.
   *
   * @param orderId the identifier of the order that was not cancelled
   * @param status the status the order is in
   * @param cause the exception the domain threw
   */
  public OrderNotCancellableException(
      UUID orderId, OrderStatus status, IllegalStateException cause) {
    super("the order " + orderId + " is " + status + " and cannot be cancelled", cause);
    this.orderId = orderId;
    this.status = status;
  }

  /**
   * Returns the identifier of the order that was not cancelled.
   *
   * @return the identifier
   */
  public UUID orderId() {
    return orderId;
  }

  /**
   * Returns the status the order is in.
   *
   * @return the status
   */
  public OrderStatus status() {
    return status;
  }
}
