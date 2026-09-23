# Repository Guidelines

## Project Structure & Module Organization

AutoSense combines a Java 21/Spring Boot 3.5.3 backend with a Vue 3/TypeScript frontend.

- `src/main/java/com/chh/autosense/`: HTTP controllers, application services, `core/` orchestration, MyBatis-Flex `mapper/`, domain types, and LangChain4j `ai/` integration.
- `src/main/resources/`: configuration, SQL initialization, logging, and `prompt/` templates.
- `src/test/java/`: unit, API contract, integration, and shared test support code.
- `frontend/src/`: pages, components, layouts, router, stores, utilities, and API clients. Follow `frontend/AGENTS.md` when editing frontend files.
- `scripts/migration/`, `specs/`, and `documents/`: database migrations, feature specifications/contracts, and supporting documentation. Treat `target/` and `frontend/dist/` as generated output.

## Build, Test, and Development Commands

Run backend commands from the repository root; use `./mvnw` instead of `.\mvnw.cmd` on Unix.

- `docker compose up -d`: start MySQL, Redis, and deviceSimulator; supply an available simulator image through `SIMULATOR_IMAGE`.
- `.\mvnw.cmd spring-boot:run`: run the backend on port 8180.
- `.\mvnw.cmd verify`: compile, run unit/contract tests, and package the backend without Docker tests.
- `.\mvnw.cmd verify -Pit`: include integration tests; requires Docker and a running simulator for device scenarios.
- In `frontend/`, run `npm ci`, then `npm run dev`; `npm run build` type-checks and bundles production assets. Use Node `^22.18.0` or `>=24.12.0`.
- `npm run openapi2ts`: regenerate frontend clients from the running backend's `/v3/api-docs`.

## Coding Style & Naming Conventions

Use four-space Java indentation, PascalCase classes, camelCase members, and UPPER_SNAKE_CASE constants. Prefer constructor injection; keep persistence behind services/core and mappers. Follow `.specify/memory/constitution.md`, including resource-based prompts and English SLF4J/Log4j 2 messages.

Vue/TypeScript uses two spaces, single quotes, no semicolons, and PascalCase components. No formatter or lint script is configured.

## Testing Guidelines

Use JUnit 5, AssertJ, Mockito, WireMock, and Testcontainers. Name unit/contract classes `*Test` and integration classes `*IT`. Cover critical business behavior and bug regressions; no numeric coverage threshold is configured. Frontend changes require type-check/build validation and manual route checks.

## Commit & Pull Request Guidelines

Recent commits use `[feature]` and `[fix]` prefixes with concise Chinese summaries. Keep changes focused. PRs should explain behavior, link issues/specs, list validation, document migrations/configuration, and include screenshots for UI changes. For new features, follow `spec.md` → `plan.md` → `tasks.md` before implementation.

## Security & Configuration

Inject credentials through environment variables; never commit or log secrets. For local use, set `LLM_MODE=mock` and `DEVICE_SERVICE_BASE_URL=http://localhost:8081`. Initialize fresh databases explicitly; migrate existing databases using `scripts/migration/`.
