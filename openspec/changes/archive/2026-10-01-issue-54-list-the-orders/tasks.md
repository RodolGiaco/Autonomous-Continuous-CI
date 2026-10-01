# Tasks

## 1. Port and persistence: load every order with its items

- [x] 1.1 Add `List<Order> findAll()` to `order/application/port/out/OrderRepository.java`, documented per design decision 3 (every stored order with all its items, in no specified order, an empty list when none is stored). Implement it in the in-memory repository of `OrderServiceTest` over its map. Verify that `./mvnw -q test-compile` succeeds once task 1.2 is done
- [x] 1.2 Redeclare `List<OrderEntity> findAll()` in `OrderJpaRepository` with `@EntityGraph(attributePaths = "items")` and a comment on why (design decision 4). Implement `findAll()` in `JpaOrderRepositoryAdapter` with `@Transactional(readOnly = true)`, mapping each entity with `OrderEntityMapper::toDomain` inside the transaction. Verify by running task 1.3
- [x] 1.3 Add `findAllReturnsEveryStoredOrderOnceWithItsItemsInOrder` to `JpaOrderRepositoryAdapterTest`: save two orders with two or more items each, cancel one of them, then assert that the result holds each of them exactly once, equal in identifier, status and items (in line order) to what was saved, and that the identifiers of the result equal `SELECT id FROM orders` read with `JdbcTemplate`. Verify that `./mvnw test -Dtest=JpaOrderRepositoryAdapterTest` passes (needs Docker)

## 2. Application: the list use case

- [x] 2.1 Create `order/application/port/in/ListOrdersUseCase.java` with `List<Order> listOrders()`, documenting `@return` in the style of `GetOrderUseCase`. Make `OrderService` implement it by returning `orders.findAll()`. Verify that `./mvnw -q compile` succeeds
- [x] 2.2 Add `listsEveryStoredOrder` and `listingWithNoStoredOrderReturnsAnEmptyList` to `OrderServiceTest`, over its in-memory repository. Verify that `./mvnw test -Dtest='OrderServiceTest,OrderLayerDependencyTest'` passes, and that `theServiceHoldsOnlyTheRepositoryPort` still holds without changes

## 3. API: the list endpoint and its documentation

- [x] 3.1 Add `ListOrdersUseCase` to the constructor of `OrderController`, as a `final` field with a `@param`, and add a `list()` method mapped with `@GetMapping` that returns `List<OrderResponse>` built with `OrderApiMapper::toResponse` (design decision 1). Its Javadoc covers `@return`. Update the `OrderResponse` Javadoc to name `GET /api/orders` too. Verify by running task 3.3
- [x] 3.2 In `OrderLayerDependencyTest.theControllerHoldsOnlyTheUseCases`, add `ListOrdersUseCase` to the expected set. Verify by running task 3.3
- [x] 3.3 In `OrderControllerTest`, add `@MockitoBean private ListOrdersUseCase listOrders;` and these tests: `listingTheOrdersReturns200WithEveryOrder` (two orders, one of them cancelled; a JSON array of length 2 whose elements carry `orderId`, `status`, `items` and `totalAmount`) and `listingWithNoOrdersReturns200WithAnEmptyArray`. Verify that `./mvnw test -Dtest='OrderControllerTest,OrderLayerDependencyTest'` passes
- [x] 3.4 Add `listingTheOrdersReturnsEveryStoredOrderInTheShapeOfASingleOrder` to `OrderApiIntegrationTest` per design decision 5: create two orders through `POST /api/orders` and cancel one; `GET /api/orders` answers `200` with JSON; the listed `orderId` values have no duplicate and equal, as a set, `SELECT id FROM orders`; and for each created order, the element with its `orderId` equals the body of `GET /api/orders/{orderId}`. Verify that `./mvnw test -Dtest=OrderApiIntegrationTest` passes (needs Docker)
- [x] 3.5 Document the endpoint in `docs/reference-application.md`: a row before `GET /api/orders/{orderId}` in the Endpoints table (`GET`, `/api/orders`, no body, `200` with every order, no errors), a sentence that names `GET /api/orders` and says it returns every stored order in the shape of `GET /api/orders/{orderId}`, in no specified order and without pagination, and a `### List orders` example after `### Create an order`. Verify that `grep -nE 'GET /api/orders([^/]|$)' docs/reference-application.md` prints the sentence

## 4. Integration checks

- [x] 4.1 Run `./mvnw spotless:apply`, then verify that `./mvnw -B -q spotless:check` passes, as CI runs it
- [x] 4.2 Run `./mvnw verify` and verify that it passes
- [x] 4.3 Verify the out-of-scope list with `git diff --stat main`: nothing under `src/main/resources/db/`, `src/main/java/io/github/rodolgiaco/oms/catalog/` or `src/test/java/io/github/rodolgiaco/oms/catalog/` changed, and the existing handlers of `OrderController` keep their mappings and bodies
