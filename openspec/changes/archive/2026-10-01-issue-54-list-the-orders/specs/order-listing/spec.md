# Spec Delta

## Purpose

Defines how a client retrieves the stored orders as a collection over the HTTP API, and the shape each order takes in that collection.

## ADDED Requirements

### Requirement: List endpoint

`GET /api/orders` SHALL answer `200` with a JSON array that holds every stored order exactly once, whatever its status. The request SHALL NOT need a body or a query parameter. The order of the elements is not specified.

#### Scenario: Listing the stored orders

- **WHEN** a client sends `GET /api/orders`
- **THEN** the response status is `200`
- **AND** the body is a JSON array
- **AND** the `orderId` values in the array are exactly the identifiers of the stored orders, each one once

#### Scenario: A cancelled order is listed

- **WHEN** a client cancels an order
- **AND** then sends `GET /api/orders`
- **THEN** the array holds that order with `"status": "CANCELLED"`

#### Scenario: Listing when no order is stored

- **WHEN** a client sends `GET /api/orders` and no order is stored
- **THEN** the response status is `200`
- **AND** the body is an empty JSON array

### Requirement: A listed order has the shape of a single order

Each element of the array that `GET /api/orders` returns SHALL be equal to the body that `GET /api/orders/{orderId}` returns for the same order: the same fields, `orderId`, `status`, `items` and `totalAmount`, with the same values, and the items in the order they were given.

#### Scenario: Comparing a listed order with the same order read alone

- **WHEN** a client sends `GET /api/orders`
- **AND** sends `GET /api/orders/{orderId}` for one of the listed orders
- **THEN** the element of the array with that `orderId` is equal to the body of the single read
