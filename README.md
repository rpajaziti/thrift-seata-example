# Seata + Thrift E-Commerce Demo

A simple e-commerce example that shows how distributed transactions work across multiple services using **Seata** and **Apache Thrift**.

The idea: when a user places an order, three things need to happen — an order is saved, inventory is decremented, and the wallet is charged. These happen in different services with different databases. If any step fails, everything rolls back. That's what Seata does.

## What is Seata?

[Seata](https://seata.apache.org/) is an open-source distributed transaction framework. In this project we use its **AT (Automatic Transaction)** mode — it intercepts SQL at the datasource level, records before/after snapshots in an `undo_log` table, and rolls back automatically if something goes wrong. No manual compensation logic needed.

## What is Thrift?

[Apache Thrift](https://thrift.apache.org/) is an RPC framework. You define your service contracts in `.thrift` files, generate Java code from them, and call remote services as if they were local method calls. In this project, two of the three services communicate via Thrift over HTTP.

## Architecture

```
                         ┌───────────────────┐
                         │     Gateway       │  :8081
                         │  (REST + Thrift)  │
                         └───┬─────┬──────┬──┘
                             │     │      │
                  REST ↙     │     │      ↘ Thrift
     ┌───────────────────┐   │     │   ┌────────────────────┐
     │  order-service    │   │     │   │ inventory-service  │  :8083
     │    (REST API)     │   │     │   │   (Thrift RPC)     │
     │    :8084          │   │     │   └────────────────────┘
     └────────┬──────────┘   │     │
              │              │     │
              │ Thrift       │     │
              ↓              │     │
     ┌───────────────────┐   │     │
     │  wallet-service   │ ←─┘     │
     │   (Thrift RPC)    │  :8082  │
     └───────────────────┘         │
                                   │
              Seata Server ────────┘  :8088
```

Everything is in one Gradle project as modules, but in a real setup these would be separate microservices.

### Services

- **gateway** — the only entry point. Exposes REST endpoints, orchestrates the order flow, and starts the global transaction with `@GlobalTransactional`
- **order-service** — REST service. Saves orders to `db_orders` and calls wallet-service via Thrift to charge the user
- **wallet-service** — Thrift-only service. Manages user balances (deduct, top-up). No REST controllers
- **inventory-service** — Thrift-only service. Manages product stock. No REST controllers

### Shared modules

- **common** — Seata filters and interceptors for XID propagation (both REST and Thrift)
- **thrift-contract** — `.thrift` files and generated Java code for wallet and inventory services

## How the order flow works

1. You call `POST /api/orders` on the gateway
2. Gateway starts a Seata global transaction (`@GlobalTransactional`)
3. Gateway calls order-service (REST) → order-service saves the order and calls wallet-service (Thrift) to deduct the balance
4. Gateway calls inventory-service (Thrift) → decrements stock
5. If anything fails at any point, Seata rolls back all three databases automatically

```
  Success                                       Failure (simulateFail=true)
  -------                                       ---------------------------

  POST /api/orders                              POST /api/orders?simulateFail=true
       |                                             |
       v                                             v
  @GlobalTransactional                          @GlobalTransactional
       |                                             |
       |-- order-service      [saved]                |-- order-service      [saved]
       |     '-- wallet       [deducted]             |     '-- wallet       [deducted]
       |                                             |
       |-- inventory          [decremented]          |-- inventory          [decremented]
       |                                             |
       v                                             v
  COMMIT all                                    RuntimeException thrown!
                                                     |
                                                     v
                                                ROLLBACK all
                                                (order, wallet, inventory)
```

This works because the entire flow is **synchronous** — every call blocks until it gets a response. By the time `@GlobalTransactional` method returns, all services have done their work and registered with Seata. So Seata knows exactly when to commit or rollback.

This would **not** work with async communication (like Kafka events), because the consumer might not have processed the message yet when the method returns. For that you'd need a different pattern (Saga, transactional outbox, etc.).

## XID Propagation — how Seata knows it's the same transaction

For Seata to work across services, every service needs to know which global transaction it's part of. This is done by passing a transaction ID (XID) in HTTP headers.

**For REST calls**, Spring gives us `ClientHttpRequestInterceptor` — we add the XID header to every outgoing `RestTemplate` call automatically. See [`SeataRestTemplateInterceptor`](common/src/main/java/com/example/common/interceptor/SeataRestTemplateInterceptor.java).

**For Thrift calls**, there's no such interceptor mechanism. Thrift uses its own transport layer (`THttpClient`), so Spring interceptors don't apply here. The solution: we extend `THttpClient` into [`SeataThriftHttpClient`](common/src/main/java/com/example/common/thrift/SeataThriftHttpClient.java) and override `flush()` to inject the XID header before each RPC call.

**On the receiving side**, a servlet [`SeataFilter`](common/src/main/java/com/example/common/filter/SeataFilter.java) picks up the XID from incoming request headers and binds it to Seata's `RootContext`. This works for both REST and Thrift since both come in as HTTP requests.

## API Endpoints (Gateway :8081)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Place an order (creates order + charges wallet + decrements stock) |
| GET | `/api/orders` | List all orders |
| POST | `/api/wallets/top-up` | Add money to a user's wallet |
| GET | `/api/products` | List all products |

Swagger UI is available at `http://localhost:8081/swagger-ui.html`

### Example: place an order

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "unitPrice": 500}'
```

### Example: simulate a failure (to see Seata rollback)

```bash
curl -X POST "http://localhost:8081/api/orders?simulateFail=true" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "unitPrice": 500}'
```

All three operations (order, wallet, inventory) will go through, then the simulated failure triggers Seata to roll back everything.

### Example: top up a wallet

```bash
curl -X POST http://localhost:8081/api/wallets/top-up \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "amount": 5000}'
```

## Tech Stack

- Java 11
- Spring Boot 2.7.18
- Apache Thrift 0.16.0
- Seata 1.4.2 (AT mode)
- PostgreSQL 14
- Flyway (database migrations)
- SpringDoc OpenAPI (Swagger UI on gateway)
- Gradle multi-module

## Getting Started

### Prerequisites

- Java 11+
- Docker & Docker Compose
- Gradle

### 1. Start PostgreSQL and Seata Server

```bash
docker compose up -d
```

This starts PostgreSQL (port 5440) with three databases (`db_orders`, `db_wallet`, `db_inventory`) and the Seata server (port 8088).

### 2. Build the project

```bash
./gradlew build
```

### 3. Run the services

Start all four in separate terminals:

```bash
./gradlew :order-service:bootRun
./gradlew :wallet-service:bootRun
./gradlew :inventory-service:bootRun
./gradlew :gateway:bootRun
```

### 4. Try it out

```bash
# Top up a wallet first
curl -X POST http://localhost:8081/api/wallets/top-up \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "amount": 5000}'

# Check products (seeded by Flyway: Laptop, Phone, Headphones)
curl http://localhost:8081/api/products

# Place an order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 1, "unitPrice": 1000}'

# Check orders
curl http://localhost:8081/api/orders
```

## Project Structure

```
├── common/                  Seata XID propagation (REST interceptor + Thrift client)
├── thrift-contract/         .thrift files + generated code
├── gateway/                 REST API, orchestration, Swagger UI
├── order-service/           Order CRUD (REST), calls wallet via Thrift
├── wallet-service/          Wallet management (Thrift only)
├── inventory-service/       Stock management (Thrift only)
├── docker-compose.yml       PostgreSQL + Seata Server
└── docker/postgres/init.sql Database initialization
```
