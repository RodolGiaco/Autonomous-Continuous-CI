package io.github.rodolgiaco.oms.order.domain;

/** Stage of its lifecycle an {@link Order} is in. */
public enum OrderStatus {

  /** The order has just been created and nothing has happened to it yet. */
  CREATED,

  /** The order was cancelled and can no longer change. */
  CANCELLED
}
