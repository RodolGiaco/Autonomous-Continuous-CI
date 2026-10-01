# Design

## Context

See proposal.md - Why. The requirements are in `specs/order-listing/spec.md`.

Current state of the order module that shapes the approach:

- `OrderRepository` has `save` and `findById`. `JpaOrderRepositoryAdapter` implements both, with `@Transactional(readOnly = true)` on the query, and maps the entity to the domain inside that transaction because the items are loaded lazily.
- `OrderEntity` holds its items as an `@ElementCollection` with an `@OrderColumn(name = "line_number")`. An element collection is lazy by default, so loading N orders with `findAll()` and then touching their items issues one query for the orders and one more per order.
- `OrderController` maps `POST /api/orders`, `GET /api/orders/{orderId}` and `POST /api/orders/{orderId}/cancel`. `GET /api/orders` answers `405` today. Every order the controller returns goes through `OrderApiMapper.toResponse`.
- `OrderLayerDependencyTest` asserts the exact set of use cases the controller holds, and `OrderControllerTest` (`@WebMvcTest`) provides a `@MockitoBean` for each of them.
- The full-context tests share one cached context and one PostgreSQL container, and run one after another (no parallel execution is configured). The `orders` table therefore holds the orders of every test that ran before, and nothing deletes them.

## Goals / Non-Goals

**Goals:**

- Return each listed order through the same mapper as the single read, so the two shapes cannot drift apart.
- Load the orders and their items in a constant number of queries, whatever the number of orders.
- Follow the existing patterns for use cases, the port and the adapter, so the new code reads like the existing code.

**Non-Goals:**

- Bounding the size of the response. Without pagination, which the issue puts out of scope, the response grows with the table (see Risks).
- A stable order of the elements. Sorting is out of scope, and the spec leaves the order unspecified.

## Decisions

### 1. The body is a bare JSON array of `OrderResponse`

`GET /api/orders` returns `List<OrderResponse>`, which Spring writes as a top-level JSON array. Each element is built by `OrderApiMapper.toResponse`, the same call `get` uses.

- **Why:** the acceptance criterion asks for every stored order, each in the shape of `GET /api/orders/{orderId}`. A bare array is the direct form of that, and building every element with the same mapper makes "the same shape" hold by construction.
- **Alternative rejected:** a wrapper object such as `{"orders": [...]}`, like `ProductPageResponse` in the catalog. That wrapper exists to carry the page metadata; with no pagination it would add a record and a field name with nothing to carry. If pagination is added later, the wrapper comes with it, as a change to this capability.

### 2. A new input port, `ListOrdersUseCase`, implemented by `OrderService`

`List<Order> listOrders()` in `application/port/in`, with no command and no parameter, because the issue rules out filters and pagination. `OrderService.listOrders()` returns `orders.findAll()`.

- **Why:** one interface per use case is the project's rule, and the controller may only hold use case interfaces (`OrderLayerDependencyTest`).
- **Alternative rejected:** adding `listOrders` to `GetOrderUseCase`. It reads well, but it puts two use cases behind one interface.

### 3. `OrderRepository.findAll()` loads every order with its items

The port gains `List<Order> findAll()`, documented as returning every stored order with all its items, in no specified order, and an empty list when none is stored. `JpaOrderRepositoryAdapter.findAll()` is `@Transactional(readOnly = true)` and maps every entity with `OrderEntityMapper::toDomain` inside that transaction, as `findById` does.

### 4. `OrderJpaRepository` overrides `findAll()` with `@EntityGraph(attributePaths = "items")`

The Spring Data interface redeclares `List<OrderEntity> findAll()` and annotates it with an entity graph that fetches the items with their orders, in one query with a join.

- **Why:** the mapper reads every item of every order, so a lazy collection costs one query per order. The entity graph removes that without changing the mapping of `OrderEntity`, so `findById` and `save` keep their current behaviour. Hibernate 6 and later return each root entity once from a fetch join, so no `DISTINCT` is needed, and the `@OrderColumn` still orders the items of each order.
- **Alternatives rejected:** `FetchType.EAGER` on the collection, which changes every load of an order, and `hibernate.default_batch_fetch_size`, a global setting that affects the catalog module too, which the issue puts out of scope.

### 5. The end-to-end test compares the list with the database and with the single read

`OrderApiIntegrationTest` cannot assume an empty table (see Context). The new tests therefore:

1. create their own orders through `POST /api/orders`, and cancel one of them;
2. send `GET /api/orders` and assert `200` and a JSON content type;
3. assert that the listed `orderId` values, as a list, have no duplicate and that, as a set, they equal `SELECT id FROM orders` read with `JdbcTemplate`, a source independent of the code under test;
4. for each order they created, assert that the element of the array with that `orderId` is equal to the parsed body of `GET /api/orders/{orderId}`.

The empty list is covered by `OrderControllerTest` and `OrderServiceTest`, where nothing else stores orders.

## Risks / Trade-offs

- **[The response grows with the table]** Every stored order and its items are loaded into memory and written in one response. → Accepted, because the issue puts pagination out of scope. The `order-listing` capability is where pagination goes when the number of orders makes it necessary.
- **[`OrderControllerTest` stops loading its context]** The `@WebMvcTest` slice cannot build `OrderController` without a `ListOrdersUseCase` bean. → Add a `@MockitoBean ListOrdersUseCase` in the same change.
- **[`OrderLayerDependencyTest` fails]** The controller now holds four use cases. → Add `ListOrdersUseCase` to the expected set. The service-side assertion stays as it is.
- **[The in-memory repository of `OrderServiceTest` stops compiling]** It implements `OrderRepository`, which gains `findAll()`. → Implement it there over the map.

## Migration Plan

- **Deploy:** code only. No schema or data change.
- **Rollback:** revert the code. Nothing is written by the new endpoint, so no data depends on it.
