# Design & Architecture Notes

This document explains the key architectural decisions in this project, the
concurrency and security approach, known deployment pitfalls encountered
during development, and what each regression test proves.

## Architecture overview

The application follows a standard layered structure:

- `web/`:  REST controllers, one per resource area (admin/lifecycle, stats, transcription)
- `service/`: business logic, exposed behind interfaces where multiple implementations exist
- `config/`: Spring beans for cross-cutting infrastructure (clock, token tracking, shutdown coordination, HTTP client, config binding)
- `dto/`: plain data-carrying records for request/response bodies
- `exception/`: custom exceptions and a single global exception handler

All dependencies are injected via constructors (never field injection), and
all beans are managed by Spring (`@Component`/`@Service`/`@RestController`)
rather than manually instantiated, per standard Spring Boot practice.

## Why `TranscriptionService` is an interface

`OpenAiTranscriptionService` (the real implementation) and
`MockTranscriptionService` (a local-testing stand-in) both implement a
shared `TranscriptionService` interface. Which one is active is controlled
entirely by Spring profiles (`@Profile("local")` vs `@Profile("!local")`),
not by commented-out code or manual constructor swapping. This was a
deliberate choice to satisfy the requirement that configuration differences
between environments be handled through profiles/environment variables
rather than code changes. Switching between them requires no code
modification, only a different `--spring.profiles.active` flag (or none, for
the real service).

This also directly enables regression testing: `TranscriptionControllerTest`
substitutes a Mockito stub for `TranscriptionService`, so the controller's
behaviour can be verified without ever making a real network call to OpenAI.

## Concurrency approach

`spring.threads.virtual.enabled: true` is set in `application.yml`. This
allows Tomcat to hand each incoming HTTP request its own lightweight virtual
thread  rather than drawing from a small, fixed platform thread pool. This
matters specifically because the transcription endpoint makes a *blocking* 
outbound HTTP call to OpenAI, under a traditional fixed thread pool, enough
concurrent slow requests could exhaust all available threads and cause later
requests to queue or time out. Virtual threads let the JVM handle many more 
concurrent blocked requests cheaply, without requiring a rewrite to a 
reactive/non-blocking programming model.

`ConcurrentLoadTest` verifies this directly: it fires 250 concurrent
requests at the uptime endpoint using a virtual-thread-per-task executor on
the client side, and asserts all 250 succeed within a bounded time window.

Two places in the code rely on `java.util.concurrent.atomic.AtomicLong` /
`AtomicBoolean` rather than plain fields, specifically because they are
written to from multiple request-handling threads simultaneously:

- `TokenUsageTracker`: input/output token counters, incremented on every
  successful transcription. A plain `long` here would be susceptible to lost
  updates under concurrent access (read-modify-write is not atomic).
- `ShutdownCoordinator`: the guard preventing two simultaneous shutdown
  requests both succeeding. Uses `AtomicBoolean.compareAndSet` specifically
  because a plain `if (!flag) { flag = true; ... }` check-then-act sequence
  is itself a race condition if two threads reach it at the same instant.

`TokenUsageTrackerRaceConditionTest` proves the counter's correctness
directly: 500 concurrent threads each increment the counters 10 times, and
the test asserts the final count is exactly the expected total (5,000). This
would fail with a wrong (lower) count if `AtomicLong` were replaced with a
plain `long`, which was manually verified during development.

## Security: API token handling

`OPENAI_API_KEY` is read once via Spring's `@ConfigurationProperties`
binding (`OpenAiProperties`) from the OS environment at startup, and used
only to construct the `Authorization` header on outbound requests to
OpenAI. It is never logged, hardcoded, or written to any file. The only
place it is referenced in configuration is `application.yml`'s
`${OPENAI_API_KEY}` placeholder, which Spring resolves dynamically at
runtime. The literal value never appears anywhere in source control.

## Known deployment pitfall: unresolved placeholder on missing key

During development, running the packaged jar on a machine with no
`OPENAI_API_KEY` environment variable set did **not** fail fast at startup.
Instead, Spring silently left the literal string `${OPENAI_API_KEY}`
unresolved and passed it as-is to OpenAI's Authorization header, which
OpenAI then rejected with a 401 Unauthorized. The failure only surfaced when
a transcription was actually attempted, with a somewhat confusing error
buried in a stack trace, rather than an obvious "missing configuration"
message at startup.

Mitigation for local testing: run with `--spring.profiles.active=local` to
use `MockTranscriptionService` instead, which requires no API key at all.
This is safe because TITAN provides `OPENAI_API_KEY` automatically, so 
the real service is only ever exercised in an environment where the key 
is guaranteed to exist.

## Regression testing summary

| Test class | What it proves |
|---|---|
| `AdminControllerTest` | Uptime endpoint returns correct shape and computed value; shutdown endpoint returns 202 on first call and 409 on a duplicate request, using stubbed `ServerClock`/`ShutdownCoordinator` |
| `StatsControllerTest` | Stats endpoint correctly reports whatever `TokenUsageTracker` currently holds, using a stubbed tracker |
| `TranscriptionControllerTest` | Transcription endpoint correctly returns whatever the (stubbed) `TranscriptionService` produces, without making a real network call |
| `ConcurrentLoadTest` | The application can service 250 simultaneous blocking HTTP requests successfully within a bounded time, verifying the virtual-threads configuration under real load |
| `TokenUsageTrackerRaceConditionTest` | `TokenUsageTracker`'s counters produce the exact expected total under 500 concurrent incrementing threads, verifying `AtomicLong` prevents lost updates |