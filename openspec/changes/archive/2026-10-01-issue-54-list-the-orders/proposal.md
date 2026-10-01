# Proposal

## Why

Orders can be created, read one at a time and cancelled, but there is no way to list them: a client has to know an order's identifier before it can see the order. `GET /api/orders` answers `405` today, because only `POST` is mapped on that path.

## What Changes

- Add a list use case to the application layer that returns every stored order.
- Add a method to the order repository port that loads every stored order together with its items.
- Add the endpoint `GET /api/orders`, which answers `200` with a JSON array that holds every stored order, each in the same shape that `GET /api/orders/{orderId}` returns. With no orders stored, the array is empty.
- Document the endpoint in `docs/reference-application.md`.

Not in this change, as issue #54 states:

- Pagination, sorting or filters. The orders come back in no specified order.
- Any change to the existing endpoints.
- A new Flyway migration.
- Any change to the catalog module.

No breaking change: the existing endpoints keep their contracts. The only observable change on an existing path is that `GET /api/orders` answers `200` instead of `405`.

## Capabilities

### New Capabilities

- `order-listing`: how a client retrieves the stored orders as a collection, and the shape each listed order takes. Pagination, sorting or filters, if they are ever added, belong here.

### Modified Capabilities

None. `order-lifecycle` already requires that every order the API returns reports its status by name, and the list endpoint follows it without changing that requirement.

## Impact

- **Application** (`order/application`): a new input port `ListOrdersUseCase`, implemented by `OrderService`. The port `OrderRepository` gains `findAll()`.
- **Persistence** (`order/infrastructure/persistence`): `JpaOrderRepositoryAdapter` implements `findAll()`, and `OrderJpaRepository` loads the items together with their orders. No schema change.
- **API** (`order/api`): `OrderController` gains the list endpoint and a fourth use case. `OrderLayerDependencyTest` must accept that use case, and `OrderControllerTest` must provide a mock of it. The `OrderResponse` Javadoc names the new endpoint.
- **Docs**: `docs/reference-application.md` (endpoint table, a sentence naming `GET /api/orders`, an example).
- **Tests**: `OrderServiceTest`, `OrderControllerTest`, `OrderApiIntegrationTest`, `JpaOrderRepositoryAdapterTest`, `OrderLayerDependencyTest`.
