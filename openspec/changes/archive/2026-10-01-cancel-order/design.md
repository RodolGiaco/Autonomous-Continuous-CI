# Design

## Context

See proposal.md - Why. The requirements are in `specs/order-lifecycle/spec.md`.

Current state of the order module that shapes the approach:

- `Order` is immutable: its fields are `final`, `create` assigns `CREATED`, and `reconstitute` rebuilds an order with any given status. The domain reports refused input as `IllegalArgumentException`, and `OrderService` wraps it in a business exception (`InvalidOrderException`) that `ApiExceptionHandler` maps to a status.
- `OrderRepository` has `save` and `findById`. The `save` contract already says it replaces any order stored under the same identifier. `JpaOrderRepositoryAdapter.save` calls Spring Data `save`, which merges an entity whose identifier is already set, and `JpaOrderRepositoryAdapterTest.savingTheSameOrderTwiceKeepsASingleCopyOfIt` shows that a second save replaces the first.
- `OrderEntity` stores the status as its name in `orders.status VARCHAR(32)`, which has no check constraint. `OrderEntityMapper.toDomain` calls `OrderStatus.valueOf` on it.
- `@Transactional` lives on the adapter methods, so each port call is its own transaction.
- `OrderController` holds `CreateOrderUseCase` and `GetOrderUseCase`. `OrderLayerDependencyTest` asserts that exact set, and `OrderControllerTest` (`@WebMvcTest`) provides a `@MockitoBean` for each use case the controller needs.

## Goals / Non-Goals

**Goals:**

- Keep the transition rule in the aggregate, so that the domain test proves it without Spring.
- Reuse the existing port and schema: no new repository method, no migration.
- Follow the patterns the module already has for use cases, business exceptions and error mapping, so the new code reads like the existing code.

**Non-Goals:**

- Protecting the transition against concurrent requests (see Risks).
- A general status-transition mechanism, such as a state machine or a transition table. With two statuses it would be more code than the rule it encodes.

## Decisions

### 1. `Order.cancel()` returns a new `Order` instead of changing this one

`cancel()` returns `Order.reconstitute(id, items, CANCELLED)`, or an equivalent private constructor call, and leaves the receiver untouched.

- **Why:** the aggregate is immutable today, and its tests depend on that. It also keeps `OrderServiceTest` honest. Its in-memory repository holds the same instance the service loaded, so if `cancel()` changed that instance, a service that forgot to call `save` would still pass.
- **Alternative rejected:** a mutable `status` field with a `cancel()` that returns `void`. It breaks the immutability that the class documents and hides a missing `save` in the unit tests.

### 2. The domain refuses with `IllegalStateException`, decided by an allow-list

`cancel()` throws `IllegalStateException` unless `status == CREATED`.

- **Why:** the domain uses JDK exceptions and stays free of Spring. `IllegalStateException` is the standard signal that an operation does not fit the object's current state, as `IllegalArgumentException` is for bad input. Checking that the status is `CREATED`, rather than that it is not `CANCELLED`, means any status added later is refused until a change allows it.
- **Alternatives rejected:** a domain exception class, which the domain does not use anywhere else, and a `canCancel()` query that the service checks, which would move the rule out of the aggregate.

### 3. A new input port, `CancelOrderUseCase`, implemented by `OrderService`

`Order cancelOrder(UUID orderId)` in `application/port/in`, with no command record, the same as `GetOrderUseCase`, because the only input is an identifier. `OrderService` then:

1. loads the order with `findById`, or throws `OrderNotFoundException`;
2. calls `order.cancel()` inside a `try` that wraps only that call, and turns `IllegalStateException` into `OrderNotCancellableException`;
3. calls `save` with the cancelled order and returns it.

`OrderNotCancellableException` lives in `order/application` next to `OrderNotFoundException` and follows its shape: it is built from the order identifier and its current status, exposes both through accessors, and keeps the domain exception as its cause. Its message names the identifier and the status, for example `the order <id> is CANCELLED and cannot be cancelled`.

- **Why:** one interface per use case is the project's rule, and the controller may only hold use case interfaces (`OrderLayerDependencyTest`). Wrapping only the `cancel()` call stops an unrelated `IllegalStateException` from being reported as a 409.
- **Alternative rejected:** adding `cancelOrder` to `GetOrderUseCase`. It mixes a command into a query port and breaks the one-use-case-per-interface rule.

### 4. `POST /api/orders/{orderId}/cancel` with no body, answering `200` with `OrderResponse`

`OrderController` gains `CancelOrderUseCase` in its constructor and a method mapped with `@PostMapping("/{orderId}/cancel")`. The method takes `@PathVariable UUID orderId` and returns `OrderApiMapper.toResponse(...)`, so it answers `200` and the body has the same shape that `get` returns.

- **Why:** the path and the status code come from the acceptance criteria. An action sub-resource keeps the transition explicit and gives the error mapping one case to handle.
- **Alternatives rejected:** `PATCH /api/orders/{orderId}` with `{"status": "CANCELLED"}`, which would have to validate arbitrary target statuses and does not match the criteria, and `DELETE`, which would suggest that the order disappears.

### 5. A `409` handler in `ApiExceptionHandler`, with the existing handlers for 404 and 400

`@ExceptionHandler(OrderNotCancellableException.class)` builds `ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage())` and sets the title to `Order cannot be cancelled`, the same way `handleDuplicateSku` does. An unknown identifier is already answered with `404` by `handleOrderNotFound`, and a malformed UUID with `400` by the `ResponseEntityExceptionHandler` superclass. Neither needs new code.

### 6. Persistence through the existing `save`, with no new port method and no migration

The cancelled order is stored with `OrderRepository.save`, which merges it over the stored row. `CANCELLED` fits in `VARCHAR(32)`, the column has no check constraint, and `OrderEntity` maps the status as a `String`, so Hibernate's `validate` and Flyway are unaffected.

- **Why:** the port contract already covers replacing an order, and the out-of-scope list forbids a migration.
- **Alternative rejected:** a port method such as `cancel(UUID)` that runs `UPDATE orders SET status = 'CANCELLED' WHERE id = ? AND status = 'CREATED'`. It would also close the race described under Risks, but it moves the transition rule into SQL and outside the aggregate. If more transitions are added, it is worth reconsidering it together with a version column.

## Risks / Trade-offs

- **[Two concurrent cancellations of the same `CREATED` order]** Both read `CREATED` in separate transactions, both save `CANCELLED`, and both callers get `200` instead of one `200` and one `409`. → The stored result is correct and no data is lost; only the second caller's status code is wrong. Closing the race needs optimistic locking (a version column, so a migration, which is out of scope) or the conditional update from Decision 6. This is accepted as a known limitation for two statuses. A later change that adds a transition that can race with cancellation, such as shipping, has to close it.
- **[`OrderControllerTest` stops loading its context]** The `@WebMvcTest` slice cannot build `OrderController` without a `CancelOrderUseCase` bean. → Add a `@MockitoBean CancelOrderUseCase` in the same change.
- **[`OrderLayerDependencyTest` fails]** The controller now holds three use cases. → Add `CancelOrderUseCase` to the expected set. The service-side assertion (`OrderRepository` only) stays as it is.
- **[The stated test count goes stale]** `docs/reference-application.md`, `README.md` and `README.es.md` say that `./mvnw verify` runs 177 tests. → Update all three to the total the final `./mvnw verify` run reports.

## Migration Plan

- **Deploy:** code only. There is no schema or data migration, and existing `CREATED` rows read back unchanged.
- **Rollback:** once any order has been cancelled, the previous version cannot read it, because `OrderStatus.valueOf("CANCELLED")` throws in `OrderEntityMapper.toDomain` and `GET` of that order fails with a `500`. Before rolling back, decide what those rows become, for example by restoring a database backup taken before the deploy or by moving them out of `orders`. Reverting the code alone is not a safe rollback.