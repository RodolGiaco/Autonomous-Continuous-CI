# order-lifecycle Specification

## Purpose
Defines the statuses an order can be in, the transitions the system allows between them, and how the HTTP API carries out or refuses each transition.

## Requirements

### Requirement: Order statuses

Every order SHALL be in exactly one of two statuses: `CREATED` or `CANCELLED`. A newly created order SHALL be in `CREATED`. The API SHALL report the status by its name in the `status` field of every order it returns.

#### Scenario: A new order starts in CREATED

- **WHEN** a client creates an order with `POST /api/orders`
- **THEN** the response body has `"status": "CREATED"`

#### Scenario: A stored order reports its status

- **WHEN** a client reads an existing order with `GET /api/orders/{orderId}`
- **THEN** the response body has the order's current status, `CREATED` or `CANCELLED`

### Requirement: Cancellation only from CREATED

The system SHALL allow an order to be cancelled only while it is in `CREATED`. Cancelling SHALL move the order to `CANCELLED` and SHALL leave its identifier, items and total unchanged. The system SHALL refuse to cancel an order in any other status and SHALL leave that order unchanged.

#### Scenario: Cancelling a created order

- **WHEN** an order in `CREATED` is cancelled
- **THEN** the order is in `CANCELLED`
- **AND** its identifier, items and total are the same as before

#### Scenario: Cancelling a cancelled order is refused

- **WHEN** an order in `CANCELLED` is cancelled again
- **THEN** the cancellation is refused
- **AND** the order stays in `CANCELLED`

### Requirement: Cancel endpoint

`POST /api/orders/{orderId}/cancel` SHALL cancel the order with that identifier and answer `200` with the cancelled order, in the same shape that `GET /api/orders/{orderId}` returns. The request SHALL NOT need a body.

#### Scenario: Cancelling a created order over HTTP

- **WHEN** a client sends `POST /api/orders/{orderId}/cancel` for an order in `CREATED`
- **THEN** the response status is `200`
- **AND** the body has the same `orderId`, `items` and `totalAmount` as before, and `"status": "CANCELLED"`

### Requirement: A cancellation is stored

A successful cancellation SHALL be stored before the response is sent, so that every later read of the order returns `CANCELLED`.

#### Scenario: Reading an order after cancelling it

- **WHEN** a client cancels an order in `CREATED`
- **AND** then sends `GET /api/orders/{orderId}` for the same order
- **THEN** the response status is `200`
- **AND** the body has `"status": "CANCELLED"`

### Requirement: Refusing to cancel an order that is not in CREATED

When the order exists but is not in `CREATED`, `POST /api/orders/{orderId}/cancel` SHALL answer `409` with a `ProblemDetail` (RFC 9457) titled `Order cannot be cancelled`, and SHALL NOT change the order.

#### Scenario: Cancelling an order that is already cancelled

- **WHEN** a client sends `POST /api/orders/{orderId}/cancel` for an order in `CANCELLED`
- **THEN** the response status is `409`
- **AND** the body is a `ProblemDetail` with `"status": 409` and `"title": "Order cannot be cancelled"`
- **AND** a later `GET /api/orders/{orderId}` returns `"status": "CANCELLED"`

### Requirement: Refusing to cancel an order that cannot be identified

`POST /api/orders/{orderId}/cancel` SHALL answer `404` with a `ProblemDetail` titled `Order not found` when no order has the identifier, and `400` with a `ProblemDetail` when the identifier is not a valid UUID. Neither case SHALL change any order.

#### Scenario: Cancelling an unknown order

- **WHEN** a client sends `POST /api/orders/{orderId}/cancel` with a well-formed identifier that no order has
- **THEN** the response status is `404`
- **AND** the body is a `ProblemDetail` with `"status": 404` and `"title": "Order not found"`

#### Scenario: Cancelling with a malformed identifier

- **WHEN** a client sends `POST /api/orders/not-a-uuid/cancel`
- **THEN** the response status is `400`
- **AND** the body is a `ProblemDetail` with `"status": 400`
