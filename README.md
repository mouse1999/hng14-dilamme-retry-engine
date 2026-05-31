# Retry Engine — Distributed Resilience Prototype

A high-throughput, non-blocking HTTP request processing engine built with **Spring Boot 3.5.14** and **SQLite**. It handles unreliable downstream APIs by processing requests asynchronously, applying exponential backoff with full jitter, and tracking full lifecycle execution state across a persistent attempt history.

Built as an Infrastructure & Resilience at Scale prototype for **Dilamme's** fintech and e-commerce platforms — designed to isolate core application threads from downstream failures and prevent cascading outages.

---

## Technical Highlights

- **Non-blocking:** `POST /request` persists intent and returns `202 Accepted` immediately. A background worker does the actual call.
- **Decoupled contracts:** Outbound API endpoints serve flat DTOs, bypassing deep Jackson serialization loops.
- **Infrastructure isolation:** Built around a database-driven polling loop (`@Scheduled` + `nextRetryAt` indexed queries) — no thread-sleep, no blocking retries.
- **Strict resilience boundary:** Retries on 5xx and network-level I/O failures (`ResourceAccessException`). Drops 4xx client errors straight to terminal failure — no wasted cycles on bad requests.
- **Dead-letter table:** Exhausted requests are moved to a separate `dead_letter` table — inspectable and queryable independently of the main queue.

---

## Setup

### Prerequisites

- Java 17+
- Maven 3.8+
- `curl` and `jq` (for the test script — Git Bash on Windows)

### Install and start

```bash
# Clone and build
git clone <repo-url>
cd retry-engine
mvn clean package -DskipTests

# Run
java -jar target/retry-engine-1.0.0.jar
```

The server starts on **port 8080**. SQLite creates `retry-engine.db` in the current directory on first boot. Hibernate creates all three tables automatically (`ddl-auto=update`).

### Run the test script

```bash
chmod +x test-script.sh
./test-script.sh
```

Runs four scenarios automatically:
1. A request that fails 3× then succeeds — backoff doubling clearly visible
2. A 4xx that is never retried — fails on attempt 1 and stops
3. A request that hits `maxRetries` and is dead-lettered
4. `GET /requests?status=FAILED` listing all failed requests

> **Windows users:** Run from Git Bash or WSL. `jq` must be installed (`sudo apt install jq` in WSL, or download from [jqlang.github.io](https://jqlang.github.io/jq/)).

---

## API Reference — curl commands

### Submit a request
```bash
curl -X POST http://localhost:8080/request \
  -H "Content-Type: application/json" \
  -d '{
    "url":        "http://localhost:8080/mock/target?id=demo",
    "method":     "GET",
    "maxRetries": 5,
    "backoffMs":  1000
  }'
```
Returns **immediately** with `202 Accepted`:
```json
{ "id": "uuid", "status": "PENDING" }
```

### Get request + full attempt history
```bash
curl http://localhost:8080/requests/{id} | jq .
```

### Filter requests by status
```bash
curl "http://localhost:8080/requests?status=PENDING"
curl "http://localhost:8080/requests?status=RETRYING"
curl "http://localhost:8080/requests?status=COMPLETED"
curl "http://localhost:8080/requests?status=FAILED"
```

### Dead-letter endpoints
```bash
# All dead-letter entries, newest first
curl http://localhost:8080/dead-letter | jq .

# Filter by reason
curl "http://localhost:8080/dead-letter?reason=MAX_RETRIES_EXCEEDED"
curl "http://localhost:8080/dead-letter?reason=TERMINAL_4XX"

# Dead-letter entry for a specific request
curl http://localhost:8080/dead-letter/{requestId} | jq .
```

### Mock endpoints (built-in, for testing)
```bash
# Flaky endpoint — returns 503 for first 3 calls, then 200
curl "http://localhost:8080/mock/target?id=demo"

# Always returns 404 — demonstrates terminal 4xx behaviour
curl http://localhost:8080/mock/always-fail-4xx

# Reset mock counter between test runs
curl -X DELETE "http://localhost:8080/mock/reset?id=demo"
```

---

## Architecture

![System Architecture Diagram](design/images/architecture.png)

### Retry flow detail

![Flow Diagram](design/images/flow.png)



### Database schema

**`requests`** — one row per submitted request, updated on every attempt.

| Column | Type | Notes |
|---|---|---|
| id | UUID | PK, generated |
| url | TEXT | target URL |
| method | TEXT | GET / POST / etc. |
| body | TEXT | nullable |
| status | TEXT | PENDING → RETRYING → COMPLETED \| FAILED |
| maxRetries | INTEGER | default 5 |
| backoffMs | INTEGER | default 1000ms |
| attemptCount | INTEGER | incremented per attempt |
| nextRetryAt | DATETIME | worker picks rows where this ≤ now() |
| lastError | TEXT | nullable |
| result | TEXT | response body on success |
| createdAt | DATETIME | auto-set |
| updatedAt | DATETIME | auto-set on save |

**`attempts`** — one row per attempt, never updated.

| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| requestId | UUID | FK → requests.id |
| attemptNumber | INTEGER | 1-indexed |
| attemptedAt | DATETIME | when the call fired |
| statusCode | INTEGER | null if network error |
| outcome | TEXT | SUCCESS \| RETRYABLE_ERROR \| TERMINAL_ERROR |
| errorMessage | TEXT | nullable |
| waitedMs | INTEGER | jittered wait before this attempt (0 for attempt 1) |

**`dead_letter`** — one row per exhausted request, never modified.

| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| requestId | UUID | original request reference (not FK — survives pruning) |
| url | TEXT | |
| method | TEXT | |
| reason | TEXT | MAX_RETRIES_EXCEEDED \| TERMINAL_4XX |
| totalAttempts | INTEGER | |
| lastError | TEXT | |
| originalCreatedAt | DATETIME | |
| deadLetteredAt | DATETIME | when it landed here |

---

## Core Concepts

### Why exponential backoff?

When an external service fails, it's usually under load or recovering from an incident. If every client retries on a fixed 1-second interval, you get a synchronized wave of requests hitting the already-struggling service every second — making the outage worse.

Exponential backoff spreads retries out: 1s → 2s → 4s → 8s → 16s. Each gap is twice the previous, which gives the recovering service meaningful breathing room between attempts. A fixed increment (1s, 2s, 3s) still produces heavy load quickly — only doubling creates a gap that grows fast enough to matter.

### Why jitter?

Backoff alone doesn't solve everything. If 1000 clients all submit requests at the same moment — say, triggered by the same upstream event — they'll retry at identical intervals: all of them at 1s, all at 2s, all at 4s. Still a synchronized thundering herd, just slower.

Jitter breaks the synchronization. Each attempt multiplies the base wait by a random factor in `[0.8, 1.2)`, re-rolled independently per attempt. Two clients nominally waiting 1s will actually wait 830ms and 1170ms. Across thousands of clients this spreads the retry load across a ~40% window instead of a single instant — turning a spike into a manageable drizzle.

Jitter is re-rolled per attempt rather than fixed once so clients don't accidentally re-synchronize on later retries.

### Why are some errors not retried?

**4xx errors** mean the problem is with the request itself — it's malformed, unauthorized, or targeting something that doesn't exist. Retrying it will produce the same 4xx every time. Worse, retrying 4xx wastes resources and hides bugs: if your code sent a bad request, the fix is to fix the code, not try again.

**5xx errors** and network errors (timeouts, connection refused) mean the service itself had a transient problem. The request may be perfectly valid — the service just can't handle it right now. These conditions are expected to clear, so retrying is appropriate.

The classifier in `RetryWorker` makes this boundary explicit:
- `HttpClientErrorException` (4xx) → `TERMINAL_ERROR`, immediate dead-letter
- `HttpServerErrorException` (5xx) → `RETRYABLE_ERROR`, schedule next attempt
- `ResourceAccessException` (network/timeout) → `RETRYABLE_ERROR`, schedule next attempt

---

## Sample Response — GET /requests/:id

```json
{
  "request": {
    "id": "bd0e5dd9-dbc2-49d1-a754-cf4529d10ac9",
    "url": "http://localhost:8080/mock/target?id=test1",
    "method": "GET",
    "status": "COMPLETED",
    "maxRetries": 5,
    "backoffMs": 1000,
    "attemptCount": 4,
    "lastError": null,
    "result": "{\"message\":\"Success!\",\"callNumber\":4}",
    "createdAt": "2024-05-31T00:02:08Z",
    "updatedAt": "2024-05-31T00:02:16Z"
  },
  "attempts": [
    { "attemptNumber": 1, "outcome": "RETRYABLE_ERROR", "statusCode": 503, "waitedMs": 0    },
    { "attemptNumber": 2, "outcome": "RETRYABLE_ERROR", "statusCode": 503, "waitedMs": 956  },
    { "attemptNumber": 3, "outcome": "RETRYABLE_ERROR", "statusCode": 503, "waitedMs": 2134 },
    { "attemptNumber": 4, "outcome": "SUCCESS",         "statusCode": 200, "waitedMs": 3887 }
  ]
}
```

`waitedMs` doubles each attempt (with jitter): 0 → ~1s → ~2s → ~4s. This is the backoff working exactly as designed.

---

## What I Struggled With

**Spring Retry was the wrong tool.** My first instinct was `@Retryable` — it's a Spring project, the task says "retry engine," it seems obvious. But Spring Retry blocks the calling thread between attempts. A 5-retry sequence with 1s → 2s → 4s → 8s → 16s backoff means the HTTP thread hangs for 31 seconds before `POST /request` can respond. That directly breaks the core requirement. The right tool was `@Scheduled` + `nextRetryAt` in the DB — the worker does the waiting, not the request thread.

**SQLite + Hibernate dialect resolution.** SQLite is not a first-class citizen in Hibernate. Getting the right dialect class took two attempts: `hibernate-community-dialects` must be added as an explicit dependency and pinned to **the exact same version** as `hibernate-core`. A version mismatch gives `ClassNotFoundException` even when the jar is present. WAL mode also had to be set via `hikari.connection-init-sql` since it must run at connection open, not at Hibernate session open.

**Backoff formula off-by-one.** I initially wrote `2^attemptCount` which produces 1, 2, 4, 8, 16 when `attemptCount` goes 0 → 4. But `attemptCount` is incremented before computing the next wait, so attempt 1 was scheduling a 2s wait instead of 1s. Fixed with `2^(attemptCount - 1)` — now attempt 1 schedules ~1s, attempt 2 ~2s, and so on.

**`@Transactional` on the scheduled method.** Without it, lazy-loaded collections threw `LazyInitializationException` and entity saves outside a Hibernate session failed silently — requests were processed but DB state never updated. Adding `@Transactional` to `processDueRequests()` opened a single session for the full tick, fixing both.

---

## What I Learned

**`nextRetryAt` as a scheduling primitive.** Storing the next retry time as a DB column and polling with a dumb loop is the foundation of every production job queue — BullMQ, Sidekiq, Celery, AWS SQS visibility timeouts all work this way. I now understand the pattern at the implementation level, not just the usage level.

**4xx vs 5xx as a hard decision boundary.** Building the explicit classifier — `HttpClientErrorException` = terminal, `HttpServerErrorException` = retryable — made this concrete. Every outbound HTTP call I write from now on will have an explicit answer to: what happens on 4xx? What happens on 5xx? What happens on timeout?

**Jitter is a coordination problem, not just a load problem.** Implementing it — and understanding why re-rolling per attempt matters — made the thundering herd intuitive. Without jitter, exponential backoff still produces synchronized retry waves. The randomness is the actual fix.

**WAL mode is non-negotiable for concurrent SQLite.** Without WAL, concurrent readers and a writer on the same SQLite file produce deadlocks. WAL gives readers non-blocking access during a write commit — essential for a single JVM with both an HTTP thread pool and a scheduler thread hitting the same file.

**Dead-letter as a separate concern.** A failed status flag and a dead-letter table solve different problems. The flag keeps the main queue query clean. The table gives you a dedicated surface for inspection, alerting, and future replay — without polluting operational queue state.

---

## Resources Consulted

- [AWS Architecture Blog — Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) — canonical explanation of full jitter vs decorrelated jitter
- [Spring Scheduling Reference](https://docs.spring.io/spring-framework/docs/current/reference/html/integration.html#scheduling) — `fixedDelay` vs `fixedRate` semantics and why `fixedDelay` is correct here
- [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) — WAL pragma config and connection initialisation
- [hibernate-community-dialects](https://github.com/hibernate/hibernate-orm/tree/main/dialects) — SQLite dialect version pinning for Hibernate 6
- [Spring RestTemplate exception hierarchy](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/client/RestTemplate.html) — mapping HTTP error categories to exception types

---

## Why This Project Made Me a Better Backend Developer

Before this, "retry logic" meant `@Retryable` and moving on. Now I understand why that fails for async, user-facing APIs: it's invisible to the caller, unrecoverable on crash, and untestable without real delays.

The pattern I've built here — persist intent, compute `nextRetryAt`, poll with a dumb loop — is the mental model I'll carry into every queue-backed system I work on. It demystified how BullMQ, Sidekiq, and SQS actually work under the hood.

More practically: I now think about every outbound HTTP call in terms of its failure modes. What's the retry policy? What's the terminal condition? What happens at max retries? These aren't edge cases to handle later — they're the normal operating conditions of distributed systems, and designing for them upfront is what separates resilient services from fragile ones.
