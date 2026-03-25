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

**For REST calls**, Spring gives us `ClientHttpRequestInterceptor` — we add the XID header to every outgoing `RestClient` call automatically. See [`SeataHttpRequestInterceptor`](common/src/main/java/com/example/common/interceptor/SeataHttpRequestInterceptor.java).

**For Thrift calls**, there's no such interceptor mechanism. Thrift uses its own transport layer (`THttpClient`), so Spring interceptors don't apply here. The solution: we extend `THttpClient` into [`SeataThriftHttpClient`](common/src/main/java/com/example/common/thrift/SeataThriftHttpClient.java) and override `flush()` to inject the XID header before each RPC call.

**On the receiving side**, a servlet [`SeataFilter`](common/src/main/java/com/example/common/filter/SeataFilter.java) picks up the XID from incoming request headers and binds it to Seata's `RootContext`. This works for both REST and Thrift since both come in as HTTP requests.

## The `undo_log` table

Every service database (`db_orders`, `db_wallet`, `db_inventory`) contains an `undo_log` table. This is required by Seata's AT mode.

When a service executes SQL within a global transaction, Seata's datasource proxy automatically captures a **before-image** and **after-image** of the affected rows and stores them in `undo_log`. If the global transaction needs to roll back, Seata reads these snapshots and generates reverse SQL to restore the data to its original state — no manual compensation code needed.

The table is created via Flyway migration in each service and has a fixed schema defined by Seata:

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

On successful commit, Seata cleans up the `undo_log` entries asynchronously. On rollback, it uses them to revert changes, then deletes them.

## Known Issue: Manual Thrift Servlet Registration

The Thrift server controllers (`WalletThriftController`, `InventoryThriftController`) are registered manually via `ThriftServerConfig` in each service instead of using the library's `@ThriftController` annotation. This is temporary.

**Why:** The `thrift-spring-boot-starter` library (v2.0.1) creates controller instances via `BeanUtils.instantiateClass` — plain reflection with a no-arg constructor. This bypasses Spring's dependency injection entirely, leaving all injected fields null at runtime.

**Affected files:**
- [`wallet-service/.../config/ThriftServerConfig.java`](wallet-service/src/main/java/com/example/walletservice/config/ThriftServerConfig.java)
- [`inventory-service/.../config/ThriftServerConfig.java`](inventory-service/src/main/java/com/example/inventoryservice/config/ThriftServerConfig.java)

**Planned fix:** Fork [thrift-spring-boot-starter](https://github.com/jmkeyes/thrift-spring-boot-starter) and replace `BeanUtils.instantiateClass` with `ApplicationContext.getAutowireCapableBeanFactory().createBean()` in `ThriftControllerRegistrar`, enabling full Spring DI for `@ThriftController` beans. An alternative would be contributing the fix upstream via PR. Once resolved, the manual `ThriftServerConfig` classes can be replaced with `@ThriftController` + `@EnableThriftController`.

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- Apache Thrift 0.22.0
- Seata 2.0.0 (AT mode)
- PostgreSQL 14
- Flyway (database migrations)
- SpringDoc OpenAPI 2 (Swagger UI on gateway)
- Gradle 8.7 multi-module

## Getting Started

### Prerequisites

- Java 21
- Docker & Docker Compose
- Gradle
- Apache Thrift compiler — needed to generate Java code from `.thrift` files
  - **Mac:** `brew install thrift`
  - **Linux:** `apt install thrift-compiler`
  - **Windows:** download the [thrift.exe](https://thrift.apache.org/download) and add it to your PATH

### 1. Start PostgreSQL and Seata Server

```bash
docker compose up -d
```

This starts PostgreSQL (port 5440) with three databases (`db_orders`, `db_wallet`, `db_inventory`) and the Seata server (port 8088).

### 2. Generate Thrift code and publish locally

```bash
./gradlew :thrift-contract:publishToMavenLocal
```

This generates Java classes from the `.thrift` files and publishes them to your local Maven repo so the other modules can depend on them.

### 3. Build the project

```bash
./gradlew build
```

### 4. Run the services

Start all four in separate terminals:

```bash
./gradlew :order-service:bootRun
./gradlew :wallet-service:bootRun
./gradlew :inventory-service:bootRun
./gradlew :gateway:bootRun
```

## API Endpoints (Gateway :8081)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Place an order (creates order + charges wallet + decrements stock) |
| GET | `/api/orders` | List all orders |
| POST | `/api/wallets/top-up` | Add money to a user's wallet |
| GET | `/api/products` | List all products |

Swagger UI is available at `http://localhost:8081/swagger-ui/index.html`

### Top up a wallet

You need to top up a wallet before placing an order — otherwise the order will fail because the user has no balance.

```bash
curl -X POST http://localhost:8081/api/wallets/top-up \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "amount": 5000}'
```

### Check available products

Products are seeded by Flyway (Laptop, Phone, Headphones). Use this to see product IDs and stock before placing an order.

```bash
curl http://localhost:8081/api/products
```

### Place an order

This creates an order, charges the user's wallet, and decrements product stock — all within a single Seata global transaction.

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "unitPrice": 500}'
```

### List all orders

```bash
curl http://localhost:8081/api/orders
```

### Simulate a failure (to see Seata rollback)

This places an order normally, then throws an exception at the end. Seata detects the failure and rolls back all three databases (order, wallet, inventory) using the `undo_log` snapshots.

```bash
curl -X POST "http://localhost:8081/api/orders?simulateFail=true" \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1", "productId": 1, "quantity": 2, "unitPrice": 500}'
```

## Tested With

This project has been tested and works with the following stacks:

| Java | Spring Boot | Seata | Thrift Starter | Commit |
|------|-------------|-------|----------------|--------|
| 11 | 2.7.18 | 1.5.2 | `spring-thrift-starter:3.0.1` | [`1f2e518`](https://github.com/rpajaziti/thrift-seata-example/commit/1f2e518) |
| 17 | 2.7.18 | 1.7.1 | `spring-thrift-starter:3.0.1` | [`3a2e19c`](https://github.com/rpajaziti/thrift-seata-example/commit/3a2e19c) |
| 19 | 2.7.18 | 1.7.1 | `spring-thrift-starter:3.0.1` | [`534801e`](https://github.com/rpajaziti/thrift-seata-example/commit/534801e) |
| 21 | 3.2.5 | 2.0.0 | `thrift-spring-boot-starter:2.0.1` | [`latest`](https://github.com/rpajaziti/thrift-seata-example) |

> **Note:** The Thrift starter library changed on the latest version. The previous versions used [`spring-thrift-starter`](https://github.com/aatarasoff/spring-thrift-starter) (`info.developerblog.spring.thrift`), which doesn't support Spring Boot 3. The latest version switched to [`thrift-spring-boot-starter`](https://github.com/jmkeyes/thrift-spring-boot-starter) (`io.github.jmkeyes`) which supports Spring Boot 3 but requires manual servlet registration (see [Known Issue](#known-issue-manual-thrift-servlet-registration) above).

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
