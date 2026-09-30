# ADR 0001 — Explicit service boundaries, minimal executable bootstrap

Status: Accepted for bootstrap

The goal is an interview portfolio with inspectable financial correctness, not a
claim of matching a private company's implementation. Use the requested Java 21,
Gradle, Javalin and PostgreSQL stack without Spring or a broker.

Choose six application projects with separate database ownership. A small runtime
library avoids duplicating technical setup. Do not create shared domain models
or speculative generic frameworks. Use direct wiring; introduce ports when a
business use case needs them.

Tradeoff: more local containers and independent migrations in exchange for visible
boundaries. No business HTTP calls are implemented in this PR.
