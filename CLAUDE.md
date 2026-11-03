# order-service

Order service of a small order and inventory system. Receives orders over REST,
publishes events to Kafka, and updates order status from inventory results.
Sister projects: `inventory-service` and `shop-infra` (compose file, event
contracts in `contracts/`).

## Stack
- Java 27 (Gradle toolchain), Spring Boot 4.1, Gradle wrapper
- PostgreSQL with Flyway migrations (JPA validates only, Flyway owns the schema)
- Apache Kafka via Spring for Apache Kafka
- JUnit 5, Mockito, Testcontainers

## Commands
- Build and test: `./gradlew build`
- Run: `./gradlew bootRun` (needs `docker compose up -d` in `shop-infra`)
- Tests only: `./gradlew test`
- Health check: `curl http://localhost:8080/actuator/health`

## Local infrastructure
- Postgres: `localhost:5433`, db/user/password `orders`
- Kafka: `localhost:9092`

## Architecture
- Packages: `controller`, `service`, `repository`, `domain`, `dto`, `messaging`, `config`
- Controllers are thin: validate input, call a service, return a DTO. Never return entities.
- Business logic lives in services. Use constructor injection.
- Order statuses: `PENDING`, `CONFIRMED`, `REJECTED`

## Events
- Topics: `orders.created` (we publish), `inventory.stock-reservation` (we consume)
- Schemas are in `shop-infra/contracts/`. Every event uses the same envelope:
  `eventId`, `eventType`, `eventVersion`, `occurredAt`, `payload`
- Use `orderId` as the Kafka message key
- Consumers must be idempotent: skip events whose `eventId` was already processed
- Do not share event classes with other services; keep our own copies

## Conventions
- Database changes only through new Flyway files (`V2__...sql`). Never edit an applied migration.
- Use records for DTOs and events. Validate requests with Bean Validation.
- Handle errors in one global exception handler with consistent error responses.
- Small methods, meaningful names, no commented-out code.
- Write a unit test for every service method and a `@WebMvcTest` for every controller.
  Integration tests use Testcontainers, not mocks of the database or Kafka.

## Rules
- Never commit secrets, `.env` files or credentials. Use environment variables.
- Do not change dependencies or the Java version without asking first.
- Show me the plan before large changes, and keep changes small and reviewable.
- Run `./gradlew build` before saying a task is done.