# Seata + Thrift E-Commerce Demo

> Distributed transactions across Spring Boot microservices — Apache Thrift RPC + REST gateway with Seata AT mode

A simple e-commerce example showing how distributed transactions work across multiple services using **Seata** and **Apache Thrift**.

When a user places an order, three things must happen atomically — an order is saved, inventory is decremented, and the wallet is charged — each in a different service with a different database. If any step fails, everything rolls back. That's what Seata does.

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
              ↓              ↓     │
     ┌───────────────────┐         │
     │  wallet-service   │  :8082  │
     │   (Thrift RPC)    │         │
     └───────────────────┘         │
                                   │
              Seata Server ────────┘  :8088
```

### Services

| Service | Port | Role |
|---------|------|------|
| **gateway** | 8081 | Only REST entry point. Orchestrates the order flow, starts global transaction with `@GlobalTransactional` |
| **order-service** | 8084 | REST service. Saves orders to `db_orders`, calls wallet-service via Thrift |
| **wallet-service** | 8082 | Thrift-only. Manages user balances (deduct, top-up) |
| **inventory-service** | 8083 | Thrift-only. Manages product stock |

### Shared modules

| Module | Purpose |
|--------|---------|
| **common** | Seata XID propagation (REST interceptor + Thrift header customizer), shared exceptions |
| **thrift-contract** | `.thrift` service definitions and generated Java code |

## How the order flow works

1. `POST /api/orders` hits the gateway
2. Gateway starts a Seata global transaction (`@GlobalTransactional`)
3. Gateway calls order-service (REST) → saves the order → calls wallet-service (Thrift) to deduct balance
4. Gateway calls inventory-service (Thrift) → decrements stock
5. If anything fails, Seata rolls back all three databases automatically

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
  COMMIT all                                    RuntimeException thrown
                                                     |
                                                     v
                                                ROLLBACK all
                                                (order + wallet + inventory)
```

This works because the flow is **synchronous** — every call blocks until it gets a response, so by the time `@GlobalTransactional` returns, all services have registered with Seata. This would not work with async messaging (Kafka, etc.) — for that you'd need a Saga or transactional outbox pattern.

## Seata XID Propagation

For Seata to coordinate a rollback across services, every service must know which global transaction it belongs to. Seata assigns a transaction ID (XID) when `@GlobalTransactional` starts, and every downstream service must receive it.

**REST calls** use Spring's `ClientHttpRequestInterceptor`. [`SeataHttpRequestInterceptor`](common/src/main/java/com/example/common/interceptor/SeataHttpRequestInterceptor.java) adds the XID header to every outgoing `RestClient` call automatically.

**Thrift calls** use [`ThriftClientHeaderCustomizer`](common/src/main/java/com/example/common/config/ThriftConfig.java) — a hook provided by `spring-thrift-starter`. The library calls `headers()` before every Thrift method invocation and injects the returned map into the HTTP transport. The customizer reads `RootContext.getXID()` and returns it as a header. The library itself has no Seata dependency — it just calls the hook.

**On the receiving side**, [`SeataFilter`](common/src/main/java/com/example/common/filter/SeataFilter.java) reads the XID from incoming request headers and binds it to Seata's `RootContext`. This works for both REST and Thrift since both arrive as HTTP requests.

## Thrift Client — Service Discovery & Connection Pooling

Thrift clients use `@ThriftClient` from [`spring-thrift-starter`](https://github.com/rpajaziti/spring-thrift-starter), which provides:

- **Connection pooling** — clients are pooled via Apache Commons Pool2 and reused across calls instead of created per-request
- **Load balancing** — backed by Spring Cloud LoadBalancer; if multiple instances are registered under the same service name, requests are distributed across them automatically
- **Service discovery** — configured via `spring.cloud.discovery.client.simple.instances` for local development; swap for Eureka or Consul in production — the `@ThriftClient` annotation and all service code stay unchanged

```yaml
# Local dev — static instances
spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            wallet-service:
              - uri: http://localhost:8082
            inventory-service:
              - uri: http://localhost:8083

# Per-service config (path to Thrift endpoint + timeouts)
wallet-service:
  path: /wallet-service/api
  connectTimeout: 10000
  readTimeout: 10000
```

## Seata AT Mode & `undo_log`

Seata's **AT (Automatic Transaction)** mode intercepts SQL at the datasource level. When a service executes SQL within a global transaction, Seata captures before/after snapshots of the affected rows and writes them to an `undo_log` table. On rollback it generates reverse SQL from those snapshots — no manual compensation logic needed.

Every service database (`db_orders`, `db_wallet`, `db_inventory`) has an `undo_log` table created by Flyway:

```sql
CREATE TABLE IF NOT EXISTS undo_log (
    id            BIGSERIAL    PRIMARY KEY,
    branch_id     BIGINT       NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info BYTEA        NOT NULL,
    log_status    INT          NOT NULL,
    log_created   TIMESTAMP(0) NOT NULL,
    log_modified  TIMESTAMP(0) NOT NULL,
    CONSTRAINT ux_undo_log UNIQUE (branch_id, xid)
);
```

On successful commit, Seata cleans up `undo_log` entries asynchronously. On rollback it uses them to revert changes and then deletes them.

## Virtual Threads

All services run with `spring.threads.virtual.enabled=true` (Java 21).

Each request in this project spends most of its time **waiting** — for Postgres, for Thrift responses, for REST calls. Without virtual threads, each blocked request holds an OS thread (~1MB, limited pool). Under load you hit the limit and requests queue up.

With virtual threads, a blocked thread is parked and the OS thread is freed immediately. You can handle far more concurrent requests on the same hardware with no code changes.

**When it doesn't help:** CPU-bound work — heavy computation, image processing, encryption. There you're burning CPU, not waiting, so parking threads does nothing. Standard I/O-heavy microservices like these are the ideal use case.

## Tech Stack

| |                       |
|---|-----------------------|
| Java | 21                    |
| Spring Boot | 3.2.5                 |
| Spring Cloud | 2023.0.2              |
| Apache Thrift | 0.22.0                |
| spring-thrift-starter | 4.0.0                 |
| Seata | 2.0.0 (AT mode)       |
| PostgreSQL | 14                    |
| Flyway | migrations            |
| SpringDoc OpenAPI 2 | Swagger UI on gateway |
| Gradle | 8.7 multi-module      |

## Getting Started

### Prerequisites

- Java 21
- Docker & Docker Compose
- Apache Thrift compiler
  - **Mac:** `brew install thrift`
  - **Linux:** `apt install thrift-compiler`
  - **Windows:** download [thrift.exe](https://thrift.apache.org/download) and add to PATH

### 1. Start infrastructure

```bash
docker compose up -d
```

Starts PostgreSQL (port 5440) with three databases and the Seata server (port 8088).

### 2. Generate Thrift code and publish locally

```bash
./gradlew :thrift-contract:publishToMavenLocal
```

Generates Java classes from `.thrift` files and publishes them to local Maven.

### 3. Build

```bash
./gradlew build
```

### 4. Run the services

Start each in a separate terminal:

```bash
./gradlew :wallet-service:bootRun
./gradlew :inventory-service:bootRun
./gradlew :order-service:bootRun
./gradlew :gateway:bootRun
```

## API Reference

Base URL: `http://localhost:8081` — Swagger UI at `/swagger-ui/index.html`

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Place an order (order + wallet deduction + stock decrement in one transaction) |
| GET | `/api/orders` | List all orders |
| POST | `/api/wallets/top-up` | Add balance to a user's wallet |
| GET | `/api/products` | List products with current stock |

### Top up a wallet

Top up before placing an order, otherwise the deduction will fail.

```bash
curl -X POST http://localhost:8081/api/wallets/top-up \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "amount": 5000}'
```

### List products

Products are seeded by Flyway (Laptop, Phone, Headphones).

```bash
curl http://localhost:8081/api/products
```

### Place an order

Creates an order, charges the wallet, decrements stock — all in one Seata global transaction.

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "totalAmount": 1000}'
```

### Simulate a failure

Executes the full order flow then throws an exception. Seata rolls back all three databases.

```bash
curl -X POST "http://localhost:8081/api/orders?simulateFail=true" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "totalAmount": 1000}'
```

## Version History

| Java | Spring Boot | Seata | Thrift Starter                     | Commit |
|------|-------------|-------|------------------------------------|--------|
| 11 | 2.7.18 | 1.5.2 | `spring-thrift-starter:3.0.1`      | [`1f2e518`](https://github.com/rpajaziti/thrift-seata-example/commit/1f2e518) |
| 17 | 2.7.18 | 1.7.1 | `spring-thrift-starter:3.0.1`      | [`3a2e19c`](https://github.com/rpajaziti/thrift-seata-example/commit/3a2e19c) |
| 19 | 2.7.18 | 1.7.1 | `spring-thrift-starter:3.0.1`      | [`534801e`](https://github.com/rpajaziti/thrift-seata-example/commit/534801e) |
| 21 | 3.2.5 | 2.0.0 | `thrift-spring-boot-starter:2.0.1` | [`6a96f2b`](https://github.com/rpajaziti/thrift-seata-example/commit/6a96f2b) |
| 21 | 3.2.5 | 2.0.0 | `spring-thrift-starter:4.0.0`      | [`latest`](https://github.com/rpajaziti/thrift-seata-example) |

## Project Structure

```
├── common/                  XID propagation (REST + Thrift), shared exceptions
├── thrift-contract/         .thrift definitions + generated Java code
├── gateway/                 REST API, global transaction orchestration, Swagger UI
├── order-service/           Order persistence, calls wallet via Thrift
├── wallet-service/          Wallet management (Thrift server)
├── inventory-service/       Stock management (Thrift server)
├── docker-compose.yml       PostgreSQL + Seata Server
└── docker/postgres/init.sql Database initialisation
```
