package io.github.rodolgiaco.oms.order.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rodolgiaco.oms.TestcontainersConfiguration;
import io.github.rodolgiaco.oms.order.application.port.out.OrderRepository;
import io.github.rodolgiaco.oms.order.domain.Order;
import io.github.rodolgiaco.oms.order.domain.OrderItem;
import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// Not transactional on purpose: each call to the repository commits and reads
// back in its own transaction, so a retrieved order really comes from
// PostgreSQL and not from a persistence context that still holds it.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JpaOrderRepositoryAdapterTest {

  private static final OrderItem BOOK = new OrderItem("BOOK", 2, new BigDecimal("12.50"));

  private static final OrderItem PEN = new OrderItem("PEN", 3, new BigDecimal("1.20"));

  // A scale beyond two decimals and a scale of zero, which a fixed-scale
  // column would have changed.
  private static final OrderItem CHIP = new OrderItem("CHIP", 1000, new BigDecimal("0.005"));

  private static final OrderItem DESK = new OrderItem("DESK", 1, new BigDecimal("300"));

  @Autowired private OrderRepository repository;

  @Autowired private JdbcTemplate jdbc;

  @Test
  void persistsAnOrderAndRetrievesItWithAllItsItems() {
    Order order = Order.create(List.of(BOOK, PEN));

    repository.save(order);

    Order found = repository.findById(order.id()).orElseThrow();
    assertEquals(List.of(BOOK, PEN), found.items());
  }

  @Test
  void theRetrievedOrderPreservesIdentifierStatusQuantitiesUnitPricesAndTotal() {
    Order order = Order.create(List.of(DESK, BOOK, CHIP, PEN));

    repository.save(order);
    Order found = repository.findById(order.id()).orElseThrow();

    assertEquals(order.id(), found.id());
    assertEquals(OrderStatus.CREATED, found.status());
    assertEquals(List.of(1, 2, 1000, 3), found.items().stream().map(OrderItem::quantity).toList());
    assertEquals(
        List.of(
            new BigDecimal("300"),
            new BigDecimal("12.50"),
            new BigDecimal("0.005"),
            new BigDecimal("1.20")),
        found.items().stream().map(OrderItem::unitPrice).toList());
    // 1 * 300 + 2 * 12.50 + 1000 * 0.005 + 3 * 1.20 = 300 + 25.00 + 5.000 + 3.60
    assertEquals(new BigDecimal("333.600"), found.total());
    assertEquals(order.total(), found.total());
  }

  @Test
  void theOrderAndItsItemsAreStoredAsRowsOfTheirTables() {
    Order order = Order.create(List.of(BOOK, PEN));

    repository.save(order);

    assertEquals(
        "CREATED",
        jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id()));
    assertEquals(
        List.of("BOOK", "PEN"),
        jdbc.queryForList(
            "SELECT product_id FROM order_items WHERE order_id = ? ORDER BY line_number",
            String.class,
            order.id()));
  }

  @Test
  void savingTheSameOrderTwiceKeepsASingleCopyOfIt() {
    Order order = Order.create(List.of(BOOK, PEN));

    repository.save(order);
    repository.save(order);

    assertEquals(List.of(BOOK, PEN), repository.findById(order.id()).orElseThrow().items());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, order.id()));
  }

  @Test
  void savingTheCancelledOrderReplacesTheStoredStatusAndKeepsTheItems() {
    Order order = Order.create(List.of(BOOK, PEN));
    repository.save(order);

    repository.save(order.cancel());

    assertEquals(
        "CANCELLED",
        jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id()));
    assertEquals(
        List.of("BOOK", "PEN"),
        jdbc.queryForList(
            "SELECT product_id FROM order_items WHERE order_id = ? ORDER BY line_number",
            String.class,
            order.id()));
    Order found = repository.findById(order.id()).orElseThrow();
    assertEquals(OrderStatus.CANCELLED, found.status());
    assertEquals(List.of(BOOK, PEN), found.items());
  }

  @Test
  void findsNothingForAnUnknownIdentifier() {
    assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
  }

  // Other tests share the database, so the result holds their orders too: the
  // identifiers are compared with the table, and the orders saved here one by
  // one.
  @Test
  void findAllReturnsEveryStoredOrderOnceWithItsItemsInOrder() {
    Order created = Order.create(List.of(DESK, BOOK, CHIP));
    Order cancelled = Order.create(List.of(PEN, BOOK)).cancel();
    repository.save(created);
    repository.save(cancelled);

    List<Order> found = repository.findAll();

    List<UUID> ids = found.stream().map(Order::id).toList();
    assertEquals(ids.size(), Set.copyOf(ids).size());
    assertEquals(
        Set.copyOf(jdbc.queryForList("SELECT id FROM orders", UUID.class)), Set.copyOf(ids));
    for (Order saved : List.of(created, cancelled)) {
      Order match =
          found.stream().filter(order -> order.id().equals(saved.id())).findFirst().orElseThrow();
      assertEquals(saved.status(), match.status());
      assertEquals(saved.items(), match.items());
    }
  }
}
