package io.github.rodolgiaco.oms.order.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body returned for an order by {@code POST /api/orders}, {@code GET /api/orders/{orderId}} and
 * {@code POST /api/orders/{orderId}/cancel}, and for each order of the array that {@code GET
 * /api/orders} returns.
 *
 * @param orderId the identifier of the order
 * @param status the status of the order
 * @param items the lines of the order
 * @param totalAmount what the order costs in total
 */
public record OrderResponse(UUID orderId, String status, List<Item> items, BigDecimal totalAmount) {

  /**
   * One line of the order.
   *
   * @param productId the identifier of the product
   * @param quantity the number of units
   * @param unitPrice the price of one unit
   */
  public record Item(String productId, int quantity, BigDecimal unitPrice) {}
}
