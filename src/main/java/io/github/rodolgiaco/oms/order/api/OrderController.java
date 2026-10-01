package io.github.rodolgiaco.oms.order.api;

import io.github.rodolgiaco.oms.order.application.InvalidOrderException;
import io.github.rodolgiaco.oms.order.application.OrderNotCancellableException;
import io.github.rodolgiaco.oms.order.application.OrderNotFoundException;
import io.github.rodolgiaco.oms.order.application.port.in.CancelOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.CreateOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.GetOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.ListOrdersUseCase;
import io.github.rodolgiaco.oms.order.domain.Order;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Exposes the order use cases over HTTP.
 *
 * <p>A {@link RestController} because every method answers with a body written as JSON. It is a
 * singleton that keeps no state besides the use cases, which Spring injects through the
 * constructor. It is the entry point of every order request: it maps the HTTP shapes to the use
 * cases and back, and leaves the errors to the global exception handler.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final CreateOrderUseCase createOrder;

  private final GetOrderUseCase getOrder;

  private final ListOrdersUseCase listOrders;

  private final CancelOrderUseCase cancelOrder;

  /**
   * Creates the controller on top of the use cases it delegates to.
   *
   * @param createOrder the use case that creates orders
   * @param getOrder the use case that retrieves orders
   * @param listOrders the use case that lists every order
   * @param cancelOrder the use case that cancels orders
   */
  public OrderController(
      CreateOrderUseCase createOrder,
      GetOrderUseCase getOrder,
      ListOrdersUseCase listOrders,
      CancelOrderUseCase cancelOrder) {
    this.createOrder = createOrder;
    this.getOrder = getOrder;
    this.listOrders = listOrders;
    this.cancelOrder = cancelOrder;
  }

  /**
   * Creates an order.
   *
   * @param request the requested items
   * @return status 201 with the created order and its location
   * @throws InvalidOrderException if the domain refuses the items, answered with 400
   */
  @PostMapping
  public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
    Order order = createOrder.createOrder(OrderApiMapper.toCommand(request));
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{orderId}")
            .buildAndExpand(order.id())
            .toUri();
    return ResponseEntity.created(location).body(OrderApiMapper.toResponse(order));
  }

  /**
   * Returns an existing order.
   *
   * @param orderId the identifier of the order
   * @return the order
   * @throws OrderNotFoundException if no order has that identifier, answered with 404
   */
  @GetMapping("/{orderId}")
  public OrderResponse get(@PathVariable UUID orderId) {
    return OrderApiMapper.toResponse(getOrder.getOrder(orderId));
  }

  /**
   * Returns every stored order, each in the shape that {@link #get(UUID)} returns.
   *
   * @return every order, in no specified order, or an empty list when none is stored
   */
  @GetMapping
  public List<OrderResponse> list() {
    return listOrders.listOrders().stream().map(OrderApiMapper::toResponse).toList();
  }

  /**
   * Cancels an existing order.
   *
   * @param orderId the identifier of the order
   * @return the cancelled order
   * @throws OrderNotFoundException if no order has that identifier, answered with 404
   * @throws OrderNotCancellableException if the order is not in a status that allows a
   *     cancellation, answered with 409
   */
  @PostMapping("/{orderId}/cancel")
  public OrderResponse cancel(@PathVariable UUID orderId) {
    return OrderApiMapper.toResponse(cancelOrder.cancelOrder(orderId));
  }
}
