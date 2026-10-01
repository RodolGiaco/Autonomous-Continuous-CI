# Proposal

## Why

Orders can be created and read, but there is no way to cancel one: `OrderStatus` only has `CREATED`, and no endpoint changes an order after it is created. A client that places an order by mistake cannot undo it.

## What Changes

- Add the status `CANCELLED` to the order domain, and a domain operation that cancels an order. The operation is allowed only from `CREATED`.
- Add a cancel use case to the application layer. It loads the order, cancels it and stores the result, and it refuses an unknown order and an order that is already cancelled.
- Add the endpoint `POST /api/orders/{orderId}/cancel`, which answers `200` with the cancelled order.
- Answer an order that cannot be cancelled with `409` as a `ProblemDetail`, and an unknown order with `404`, as `GET /api/orders/{orderId}` already does.
- Document the endpoint, the new `409` error and the new status in `docs/reference-application.md`.

Not in this change:

- Any status other than `CREATED` and `CANCELLED`.
- Refunds, payments or stock changes.
- A cancellation reason, timestamp or audit field.
- A Flyway migration: the `status` column already stores the name of the status, and `VARCHAR(32)` holds `CANCELLED`.
- Any change to the catalog module.

No breaking change: existing endpoints keep their contracts, and `CREATED` orders keep reading back the same way.

## Capabilities

### New Capabilities

- `order-lifecycle`: the statuses an order can be in and the transitions between them, here creation in `CREATED` and cancellation to `CANCELLED`, with how the API exposes and refuses each transition.

### Modified Capabilities

None. No spec exists yet under `openspec/specs/`.

## Impact

- **Domain** (`order/domain`): `OrderStatus` gains `CANCELLED`; `Order` gains a cancel operation that returns the cancelled order.
- **Application** (`order/application`): a new input port `CancelOrderUseCase`, implemented by `OrderService`, and a new business exception for an order that cannot be cancelled.
- **API** (`order/api`): `OrderController` gains the cancel endpoint and a third use case. `OrderLayerDependencyTest` must accept that use case, and `OrderControllerTest` must provide a mock of it.
- **Web** (`web/ApiExceptionHandler`): a handler that turns the new exception into a `409`.
- **Persistence**: no code or schema change. The existing `OrderRepository.save` already replaces an order stored under the same identifier.
- **Docs**: `docs/reference-application.md` (endpoint table, errors table, an example), and the test count in `docs/reference-application.md`, `README.md` and `README.es.md`.
- **Tests**: `OrderTest`, `OrderServiceTest`, `OrderControllerTest`, `OrderApiIntegrationTest`, `JpaOrderRepositoryAdapterTest`, `OrderEntityMapperTest`, `OrderLayerDependencyTest`.
