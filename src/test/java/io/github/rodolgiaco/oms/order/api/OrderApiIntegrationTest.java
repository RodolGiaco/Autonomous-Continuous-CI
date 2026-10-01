package io.github.rodolgiaco.oms.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.rodolgiaco.oms.TestcontainersConfiguration;
import io.github.rodolgiaco.oms.order.application.port.out.OrderRepository;
import io.github.rodolgiaco.oms.order.domain.Order;
import io.github.rodolgiaco.oms.order.domain.OrderItem;
import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Goes through the real service and the PostgreSQL adapter. MockMvc is built
// from the context instead of through @AutoConfigureMockMvc, so this class
// shares the cached context, and its container, with the other full-context
// tests.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderApiIntegrationTest {

  @Autowired private WebApplicationContext context;

  @Autowired private OrderRepository repository;

  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void aCreatedOrderIsPersistedAndCanBeRetrieved() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"items": [
                          {"productId": "BOOK", "quantity": 2, "unitPrice": 12.50},
                          {"productId": "CHIP", "quantity": 1000, "unitPrice": 0.005}
                        ]}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.totalAmount").value(30.0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID orderId = UUID.fromString(JsonPath.read(created, "$.orderId"));

    Order stored = repository.findById(orderId).orElseThrow();
    assertEquals(OrderStatus.CREATED, stored.status());
    assertEquals(
        List.of(
            new OrderItem("BOOK", 2, new BigDecimal("12.50")),
            new OrderItem("CHIP", 1000, new BigDecimal("0.005"))),
        stored.items());

    mockMvc
        .perform(get("/api/orders/{orderId}", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[1].productId").value("CHIP"))
        .andExpect(jsonPath("$.items[1].quantity").value(1000))
        .andExpect(jsonPath("$.totalAmount").value(30.0));
  }

  @Test
  void retrievingAnUnknownOrderReturns404() throws Exception {
    mockMvc
        .perform(get("/api/orders/{orderId}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void anInvalidQuantityReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"items": [{"productId": "BOOK", "quantity": 0, "unitPrice": 12.50}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void cancellingACreatedOrderReturns200WithTheOrderCancelled() throws Exception {
    UUID orderId = createOrder();

    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value("BOOK"))
        .andExpect(jsonPath("$.items[1].productId").value("PEN"))
        .andExpect(jsonPath("$.totalAmount").value(28.60));
  }

  @Test
  void aCancelledOrderIsReadBackAsCancelled() throws Exception {
    UUID orderId = createOrder();
    mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId)).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/orders/{orderId}", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertEquals(OrderStatus.CANCELLED, repository.findById(orderId).orElseThrow().status());
  }

  @Test
  void cancellingAnAlreadyCancelledOrderReturns409AndKeepsItCancelled() throws Exception {
    UUID orderId = createOrder();
    mockMvc.perform(post("/api/orders/{orderId}/cancel", orderId)).andExpect(status().isOk());

    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", orderId))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("Order cannot be cancelled"));

    mockMvc
        .perform(get("/api/orders/{orderId}", orderId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void listingTheOrdersReturnsEveryStoredOrderInTheShapeOfASingleOrder() throws Exception {
    UUID created = createOrder();
    UUID cancelled = createOrder();
    mockMvc.perform(post("/api/orders/{orderId}/cancel", cancelled)).andExpect(status().isOk());

    String listed =
        mockMvc
            .perform(get("/api/orders"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Other tests share the database, so the listed identifiers are compared
    // with the table rather than with the orders created here.
    List<String> listedIds = JsonPath.read(listed, "$[*].orderId");
    assertEquals(listedIds.size(), Set.copyOf(listedIds).size());
    assertEquals(
        jdbc.queryForList("SELECT id FROM orders", UUID.class).stream()
            .map(UUID::toString)
            .collect(Collectors.toSet()),
        Set.copyOf(listedIds));

    for (UUID orderId : List.of(created, cancelled)) {
      String single =
          mockMvc
              .perform(get("/api/orders/{orderId}", orderId))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      Object expected = JsonPath.read(single, "$");
      List<Object> matching = JsonPath.read(listed, "$[?(@.orderId == '" + orderId + "')]");
      assertEquals(List.of(expected), matching);
    }
    List<String> statuses = JsonPath.read(listed, "$[?(@.orderId == '" + cancelled + "')].status");
    assertEquals(List.of("CANCELLED"), statuses);
  }

  @Test
  void cancellingAnUnknownOrderReturns404AsAProblemDetail() throws Exception {
    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Order not found"));
  }

  // Creates an order through the API, as a client would, and returns its
  // identifier. Its total is 2 * 12.50 + 3 * 1.20 = 28.60.
  private UUID createOrder() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"items": [
                          {"productId": "BOOK", "quantity": 2, "unitPrice": 12.50},
                          {"productId": "PEN", "quantity": 3, "unitPrice": 1.20}
                        ]}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(created, "$.orderId"));
  }
}
