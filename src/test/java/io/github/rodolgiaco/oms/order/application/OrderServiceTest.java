package io.github.rodolgiaco.oms.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rodolgiaco.oms.order.application.port.in.CreateOrderCommand;
import io.github.rodolgiaco.oms.order.application.port.out.OrderRepository;
import io.github.rodolgiaco.oms.order.domain.Order;
import io.github.rodolgiaco.oms.order.domain.OrderItem;
import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

  private static final CreateOrderCommand.Item BOOK =
      new CreateOrderCommand.Item("BOOK", 2, new BigDecimal("12.50"));

  private static final CreateOrderCommand.Item PEN =
      new CreateOrderCommand.Item("PEN", 3, new BigDecimal("1.20"));

  /** Keeps orders in a map, so the service is tested without a database. */
  private static final class InMemoryOrderRepository implements OrderRepository {

    private final Map<UUID, Order> orders = new HashMap<>();

    @Override
    public void save(Order order) {
      orders.put(order.id(), order);
    }

    @Override
    public Optional<Order> findById(UUID id) {
      return Optional.ofNullable(orders.get(id));
    }
  }

  private final InMemoryOrderRepository repository = new InMemoryOrderRepository();

  private final OrderService service = new OrderService(repository);

  @Test
  void createsAnOrderWithTheRequestedItems() {
    Order order = service.createOrder(new CreateOrderCommand(List.of(BOOK, PEN)));

    assertNotNull(order.id());
    assertEquals(OrderStatus.CREATED, order.status());
    assertEquals(
        List.of(
            new OrderItem("BOOK", 2, new BigDecimal("12.50")),
            new OrderItem("PEN", 3, new BigDecimal("1.20"))),
        order.items());
    assertEquals(new BigDecimal("28.60"), order.total());
  }

  @Test
  void theCreatedOrderIsStoredThroughTheRepository() {
    Order order = service.createOrder(new CreateOrderCommand(List.of(BOOK)));

    assertSame(order, repository.findById(order.id()).orElseThrow());
  }

  @Test
  void retrievesAStoredOrder() {
    Order order = Order.create(List.of(new OrderItem("BOOK", 2, new BigDecimal("12.50"))));
    repository.save(order);

    assertSame(order, service.getOrder(order.id()));
  }

  @Test
  void retrievingAnUnknownOrderThrowsOrderNotFound() {
    UUID unknown = UUID.randomUUID();

    OrderNotFoundException thrown =
        assertThrows(OrderNotFoundException.class, () -> service.getOrder(unknown));

    assertEquals(unknown, thrown.orderId());
  }

  @Test
  void cancelsACreatedOrderAndStoresTheCancelledOrder() {
    Order order = Order.create(List.of(new OrderItem("BOOK", 2, new BigDecimal("12.50"))));
    repository.save(order);

    Order cancelled = service.cancelOrder(order.id());

    assertEquals(OrderStatus.CANCELLED, cancelled.status());
    assertEquals(order.id(), cancelled.id());
    assertEquals(order.items(), cancelled.items());
    assertSame(cancelled, repository.findById(order.id()).orElseThrow());
  }

  @Test
  void cancellingAnUnknownOrderThrowsOrderNotFound() {
    UUID unknown = UUID.randomUUID();

    OrderNotFoundException thrown =
        assertThrows(OrderNotFoundException.class, () -> service.cancelOrder(unknown));

    assertEquals(unknown, thrown.orderId());
    assertTrue(repository.orders.isEmpty());
  }

  @Test
  void cancellingACancelledOrderThrowsOrderNotCancellableAndKeepsTheStoredOrder() {
    Order order =
        Order.reconstitute(
            UUID.randomUUID(),
            List.of(new OrderItem("BOOK", 2, new BigDecimal("12.50"))),
            OrderStatus.CANCELLED);
    repository.save(order);

    OrderNotCancellableException thrown =
        assertThrows(OrderNotCancellableException.class, () -> service.cancelOrder(order.id()));

    assertEquals(order.id(), thrown.orderId());
    assertEquals(OrderStatus.CANCELLED, thrown.status());
    assertSame(order, repository.findById(order.id()).orElseThrow());
  }

  @Test
  void refusesAnInvalidQuantityAndStoresNothing() {
    CreateOrderCommand command =
        new CreateOrderCommand(
            List.of(new CreateOrderCommand.Item("BOOK", 0, new BigDecimal("12.50"))));

    assertThrows(InvalidOrderException.class, () -> service.createOrder(command));
    assertTrue(repository.orders.isEmpty());
  }

  @Test
  void refusesAnInvalidUnitPriceAndStoresNothing() {
    CreateOrderCommand command =
        new CreateOrderCommand(
            List.of(new CreateOrderCommand.Item("BOOK", 2, new BigDecimal("-1"))));

    assertThrows(InvalidOrderException.class, () -> service.createOrder(command));
    assertTrue(repository.orders.isEmpty());
  }

  @Test
  void refusesAnOrderWithoutItems() {
    assertThrows(
        InvalidOrderException.class, () -> service.createOrder(new CreateOrderCommand(List.of())));
    assertThrows(
        InvalidOrderException.class, () -> service.createOrder(new CreateOrderCommand(null)));
  }

  @Test
  void refusesANullItem() {
    CreateOrderCommand command = new CreateOrderCommand(Arrays.asList(BOOK, null));

    assertThrows(InvalidOrderException.class, () -> service.createOrder(command));
    assertTrue(repository.orders.isEmpty());
  }
}
