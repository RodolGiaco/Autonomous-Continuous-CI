package io.github.rodolgiaco.oms.order.api;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rodolgiaco.oms.order.application.InvalidOrderException;
import io.github.rodolgiaco.oms.order.application.OrderNotCancellableException;
import io.github.rodolgiaco.oms.order.application.OrderNotFoundException;
import io.github.rodolgiaco.oms.order.application.port.in.CancelOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.CreateOrderCommand;
import io.github.rodolgiaco.oms.order.application.port.in.CreateOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.GetOrderUseCase;
import io.github.rodolgiaco.oms.order.domain.Order;
import io.github.rodolgiaco.oms.order.domain.OrderItem;
import io.github.rodolgiaco.oms.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

  private static final Order ORDER =
      Order.reconstitute(
          UUID.fromString("7f1c6a52-3b7e-4f7a-9c1e-2d4b8e6f0a11"),
          List.of(
              new OrderItem("BOOK", 2, new BigDecimal("12.50")),
              new OrderItem("PEN", 3, new BigDecimal("1.20"))),
          OrderStatus.CREATED);

  private static final String VALID_REQUEST =
      """
      {"items": [
        {"productId": "BOOK", "quantity": 2, "unitPrice": 12.50},
        {"productId": "PEN", "quantity": 3, "unitPrice": 1.20}
      ]}
      """;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CreateOrderUseCase createOrder;

  @MockitoBean private GetOrderUseCase getOrder;

  @MockitoBean private CancelOrderUseCase cancelOrder;

  @Test
  void creatingAValidOrderReturns201WithTheOrder() throws Exception {
    when(createOrder.createOrder(any())).thenReturn(ORDER);

    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", endsWith("/api/orders/" + ORDER.id())))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.orderId").value(ORDER.id().toString()))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value("BOOK"))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.items[0].unitPrice").value(12.50))
        .andExpect(jsonPath("$.items[1].productId").value("PEN"))
        .andExpect(jsonPath("$.items[1].quantity").value(3))
        .andExpect(jsonPath("$.items[1].unitPrice").value(1.20))
        .andExpect(jsonPath("$.totalAmount").value(28.60));
  }

  @Test
  void theRequestIsMappedToACommandWithTheSameItems() throws Exception {
    when(createOrder.createOrder(any())).thenReturn(ORDER);

    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isCreated());

    verify(createOrder)
        .createOrder(
            new CreateOrderCommand(
                List.of(
                    new CreateOrderCommand.Item("BOOK", 2, new BigDecimal("12.50")),
                    new CreateOrderCommand.Item("PEN", 3, new BigDecimal("1.20")))));
  }

  @Test
  void retrievingAnExistingOrderReturns200WithTheOrder() throws Exception {
    when(getOrder.getOrder(ORDER.id())).thenReturn(ORDER);

    mockMvc
        .perform(get("/api/orders/{orderId}", ORDER.id()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.orderId").value(ORDER.id().toString()))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value("BOOK"))
        .andExpect(jsonPath("$.totalAmount").value(28.60));
  }

  @Test
  void retrievingAnUnknownOrderReturns404AsAProblemDetail() throws Exception {
    UUID unknown = UUID.randomUUID();
    when(getOrder.getOrder(unknown)).thenThrow(new OrderNotFoundException(unknown));

    mockMvc
        .perform(get("/api/orders/{orderId}", unknown))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Order not found"))
        .andExpect(jsonPath("$.detail").value("no order has the identifier " + unknown))
        .andExpect(jsonPath("$.instance").value("/api/orders/" + unknown));
  }

  @Test
  void retrievingWithAMalformedIdentifierReturns400AsAProblemDetail() throws Exception {
    mockMvc
        .perform(get("/api/orders/{orderId}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(getOrder);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void anInvalidQuantityReturns400BeforeReachingTheUseCase(int quantity) throws Exception {
    String request =
        """
        {"items": [{"productId": "BOOK", "quantity": %d, "unitPrice": 12.50}]}
        """
            .formatted(quantity);

    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[*].field", hasItem("items[0].quantity")));

    verifyNoInteractions(createOrder);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-12.50"})
  void anInvalidUnitPriceReturns400BeforeReachingTheUseCase(String unitPrice) throws Exception {
    String request =
        """
        {"items": [{"productId": "BOOK", "quantity": 2, "unitPrice": %s}]}
        """
            .formatted(unitPrice);

    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[*].field", hasItem("items[0].unitPrice")));

    verifyNoInteractions(createOrder);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"items\": []}",
        "{\"items\": [null]}",
        "{\"items\": [{\"quantity\": 2, \"unitPrice\": 12.50}]}",
        "{\"items\": [{\"productId\": \" \", \"quantity\": 2, \"unitPrice\": 12.50}]}",
        "{\"items\": [{\"productId\": \"BOOK\", \"unitPrice\": 12.50}]}",
        "{\"items\": [{\"productId\": \"BOOK\", \"quantity\": 2}]}"
      })
  void anIncompleteRequestReturns400BeforeReachingTheUseCase(String request) throws Exception {
    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors.length()").value(1));

    verifyNoInteractions(createOrder);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{\"items\": [{\"productId\": \"BOOK\", \"quantity\": \"two\", \"unitPrice\": 12.50}]}"
      })
  void anUnreadableBodyReturns400BeforeReachingTheUseCase(String request) throws Exception {
    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(createOrder);
  }

  @Test
  void anOrderTheDomainRefusesReturns400AsAProblemDetail() throws Exception {
    when(createOrder.createOrder(any()))
        .thenThrow(new InvalidOrderException(new IllegalArgumentException("refused")));

    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Invalid order"))
        .andExpect(jsonPath("$.detail").value("refused"));
  }

  @Test
  void cancellingAnOrderReturns200WithTheCancelledOrder() throws Exception {
    when(cancelOrder.cancelOrder(ORDER.id())).thenReturn(ORDER.cancel());

    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", ORDER.id()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.orderId").value(ORDER.id().toString()))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value("BOOK"))
        .andExpect(jsonPath("$.totalAmount").value(28.60));
  }

  @Test
  void cancellingAnUnknownOrderReturns404AsAProblemDetail() throws Exception {
    UUID unknown = UUID.randomUUID();
    when(cancelOrder.cancelOrder(unknown)).thenThrow(new OrderNotFoundException(unknown));

    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", unknown))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Order not found"))
        .andExpect(jsonPath("$.detail").value("no order has the identifier " + unknown));
  }

  @Test
  void cancellingAnOrderThatIsNotCreatedReturns409AsAProblemDetail() throws Exception {
    when(cancelOrder.cancelOrder(ORDER.id()))
        .thenThrow(
            new OrderNotCancellableException(
                ORDER.id(), OrderStatus.CANCELLED, new IllegalStateException("refused")));

    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", ORDER.id()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.title").value("Order cannot be cancelled"))
        .andExpect(
            jsonPath("$.detail")
                .value("the order " + ORDER.id() + " is CANCELLED and cannot be cancelled"))
        .andExpect(jsonPath("$.instance").value("/api/orders/" + ORDER.id() + "/cancel"));
  }

  @Test
  void cancellingWithAMalformedIdentifierReturns400AsAProblemDetail() throws Exception {
    mockMvc
        .perform(post("/api/orders/{orderId}/cancel", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));

    verifyNoInteractions(cancelOrder);
  }
}
