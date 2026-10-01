package io.github.rodolgiaco.oms.order.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rodolgiaco.oms.order.domain.Order;
import io.github.rodolgiaco.oms.order.domain.OrderItem;
import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEntityMapperTest {

  private static final OrderItem BOOK = new OrderItem("BOOK", 2, new BigDecimal("12.50"));

  private static final OrderItem PEN = new OrderItem("PEN", 3, new BigDecimal("1.20"));

  @Test
  void anOrderSurvivesTheRoundTripThroughItsEntity() {
    Order order = Order.create(List.of(BOOK, PEN));

    Order mapped = OrderEntityMapper.toDomain(OrderEntityMapper.toEntity(order));

    assertEquals(order.id(), mapped.id());
    assertEquals(order.status(), mapped.status());
    assertEquals(order.items(), mapped.items());
    assertEquals(order.total(), mapped.total());
  }

  @Test
  void aCancelledOrderSurvivesTheRoundTripThroughItsEntity() {
    Order order = Order.create(List.of(BOOK, PEN)).cancel();

    Order mapped = OrderEntityMapper.toDomain(OrderEntityMapper.toEntity(order));

    assertEquals(order.id(), mapped.id());
    assertEquals(OrderStatus.CANCELLED, mapped.status());
    assertEquals(order.items(), mapped.items());
  }

  @Test
  void rejectsAStoredStatusTheDomainDoesNotKnow() {
    OrderEntity entity =
        new OrderEntity(
            UUID.randomUUID(),
            "SHIPPED",
            List.of(new OrderItemEmbeddable("BOOK", 1, BigDecimal.ONE)));

    assertThrows(IllegalArgumentException.class, () -> OrderEntityMapper.toDomain(entity));
  }

  // A gap in the stored line numbers reaches the mapper as a null element.
  @Test
  void rejectsStoredItemsWithAGap() {
    OrderEntity entity =
        new OrderEntity(
            UUID.randomUUID(),
            "CREATED",
            Arrays.asList(new OrderItemEmbeddable("BOOK", 1, BigDecimal.ONE), null));

    assertThrows(IllegalArgumentException.class, () -> OrderEntityMapper.toDomain(entity));
  }

  @Test
  void rejectsAStoredOrderWithoutItems() {
    OrderEntity entity = new OrderEntity(UUID.randomUUID(), "CREATED", List.of());

    assertThrows(IllegalArgumentException.class, () -> OrderEntityMapper.toDomain(entity));
  }
}
