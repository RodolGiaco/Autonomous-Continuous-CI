package io.github.rodolgiaco.oms.order.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An order placed in the order management system, and the aggregate that guards its invariants.
 *
 * <p>An order always has an identifier, a status, and at least one {@link OrderItem}. Its items
 * cannot change once it is created.
 *
 * <p>An order starts in {@link OrderStatus#CREATED}, and can be cancelled only from there. An order
 * never changes in place: {@link #cancel()} returns the cancelled order and leaves this one as it
 * was.
 */
public final class Order {

  private final UUID id;

  private final List<OrderItem> items;

  private final OrderStatus status;

  private Order(UUID id, List<OrderItem> items, OrderStatus status) {
    this.id = id;
    this.items = items;
    this.status = status;
  }

  /**
   * Creates a new order with a fresh identifier and status {@link OrderStatus#CREATED}.
   *
   * @param items the items of the order; the list is copied, so later changes to it do not reach
   *     the order
   * @return the new order
   * @throws IllegalArgumentException if the items are null, empty, or contain a null element
   */
  public static Order create(List<OrderItem> items) {
    return new Order(UUID.randomUUID(), checkedCopyOf(items), OrderStatus.CREATED);
  }

  /**
   * Rebuilds an order that already exists, such as one read back from storage.
   *
   * <p>Unlike {@link #create(List)}, this keeps the given identifier and status instead of
   * assigning new ones. The same invariants hold for the items.
   *
   * @param id the identifier the order already has
   * @param items the items of the order; the list is copied, so later changes to it do not reach
   *     the order
   * @param status the status the order is in
   * @return the rebuilt order
   * @throws IllegalArgumentException if the identifier or status is null, or the items are null,
   *     empty, or contain a null element
   */
  public static Order reconstitute(UUID id, List<OrderItem> items, OrderStatus status) {
    if (id == null) {
      throw new IllegalArgumentException("an order must have an identifier");
    }
    if (status == null) {
      throw new IllegalArgumentException("an order must have a status");
    }
    return new Order(id, checkedCopyOf(items), status);
  }

  private static List<OrderItem> checkedCopyOf(List<OrderItem> items) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("an order must contain at least one item");
    }
    // Not items.contains(null): the unmodifiable lists of List.of throw on a
    // null argument instead of answering false.
    if (items.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("an order must not contain a null item");
    }
    return List.copyOf(items);
  }

  /**
   * Cancels this order.
   *
   * <p>Only an order in {@link OrderStatus#CREATED} can be cancelled. The check names the one
   * status that allows it, so a status added later is refused until it is allowed here too.
   *
   * @return a new order with the same identifier and items, in status {@link
   *     OrderStatus#CANCELLED}; this order is left unchanged
   * @throws IllegalStateException if this order is not in {@link OrderStatus#CREATED}
   */
  public Order cancel() {
    if (status != OrderStatus.CREATED) {
      throw new IllegalStateException(
          "the order " + id + " is " + status + " and cannot be cancelled");
    }
    return new Order(id, items, OrderStatus.CANCELLED);
  }

  /**
   * Returns the identifier of this order.
   *
   * @return the identifier, never null
   */
  public UUID id() {
    return id;
  }

  /**
   * Returns the items of this order.
   *
   * @return an unmodifiable list with at least one item
   */
  public List<OrderItem> items() {
    return items;
  }

  /**
   * Returns the status of this order.
   *
   * @return the status, never null
   */
  public OrderStatus status() {
    return status;
  }

  /**
   * Returns what this order costs in total.
   *
   * @return the sum of {@code quantity * unitPrice} over every item
   */
  public BigDecimal total() {
    return items.stream().map(OrderItem::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
