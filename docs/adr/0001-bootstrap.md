# ADR 0001 â€” Explicit service boundaries, minimal executable bootstrap

Status: Accepted for bootstrap

Use explicit service boundaries and verifiable financial correctness with Java 21,
Gradle, Javalin and PostgreSQL, without Spring or a message broker.

Choose six application projects with separate database ownership. A small runtime
library avoids duplicating technical setup. Do not create shared domain models
or speculative generic frameworks. Use direct wiring; introduce ports when a
business use case needs them.

Tradeoff: more local containers and independent migrations in exchange for visible
boundaries. No business HTTP calls are implemented in this PR.
