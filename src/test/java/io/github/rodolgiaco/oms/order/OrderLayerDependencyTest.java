package io.github.rodolgiaco.oms.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.rodolgiaco.oms.order.api.OrderController;
import io.github.rodolgiaco.oms.order.application.OrderService;
import io.github.rodolgiaco.oms.order.application.port.in.CancelOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.CreateOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.GetOrderUseCase;
import io.github.rodolgiaco.oms.order.application.port.in.ListOrdersUseCase;
import io.github.rodolgiaco.oms.order.application.port.out.OrderRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Guards which collaborators the API and application layers are allowed to hold. */
class OrderLayerDependencyTest {

  private static Set<Class<?>> collaboratorsOf(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(Field::getType)
        .collect(Collectors.toSet());
  }

  @Test
  void theControllerHoldsOnlyTheUseCases() {
    assertEquals(
        Set.of(
            CreateOrderUseCase.class,
            GetOrderUseCase.class,
            ListOrdersUseCase.class,
            CancelOrderUseCase.class),
        collaboratorsOf(OrderController.class));
  }

  @Test
  void theServiceHoldsOnlyTheRepositoryPort() {
    assertEquals(Set.of(OrderRepository.class), collaboratorsOf(OrderService.class));
  }
}
