# Repository Guidelines

## Project Structure & Module Organization

This repository is a Vue 3, TypeScript, and Vite single-page application. `src/main.ts` boots the app; `src/App.vue` and `src/layouts/` define the shell. Put routed views in `src/pages/`, reusable UI in `src/components/`, route definitions in `src/router/`, and shared helpers in `src/utils/`. HTTP setup lives in `src/request.ts`; OpenAPI-generated clients and types live in `src/api/`. Bundled assets belong in `src/assets/`, while files copied unchanged belong in `public/`. Production output is generated in `dist/` and should not be edited.

## Build, Test, and Development Commands

- `npm ci`: install the exact dependencies from `package-lock.json` (Node `^22.18.0` or `>=24.12.0`).
- `npm run dev`: start Vite with hot module replacement.
- `npm run type-check`: validate TypeScript and Vue SFC types with `vue-tsc`.
- `npm run build`: run type checking and create the production bundle.
- `npm run preview`: serve the built bundle for a local production check.
- `npm run openapi2ts`: regenerate API clients from `http://localhost:8180/v3/api-docs`; ensure the backend is running first.

## Coding Style & Naming Conventions

Follow the existing handwritten code style: two-space indentation, single quotes, no semicolons, and trailing commas in multiline structures. Use `<script setup lang="ts">` and Composition API patterns for Vue components. Name components and pages in PascalCase (`GlobalHeader.vue`, `SessionsPage.vue`); use camelCase for variables and functions. Prefer `@/` imports for code under `src/`. Generated files under `src/api/` may follow generator formatting; regenerate them instead of making cosmetic edits by hand.

## Testing Guidelines

No automated test framework or coverage threshold is configured yet. Every change must at least pass `npm run type-check` and `npm run build`; manually exercise affected routes with `npm run dev`. If adding tests, introduce the framework and an `npm test` script in the same change, and use names such as `ComponentName.spec.ts`.

## Commit & Pull Request Guidelines

There is no existing commit history to establish a convention. Until one emerges, use concise, imperative Conventional Commit-style subjects, for example `feat: add device registration`. Keep commits focused. Pull requests should explain the change and motivation, link relevant issues, list validation performed, and include screenshots or recordings for visible UI changes. Call out generated API changes and configuration requirements explicitly.

## Security & Configuration

Set the backend URL with `VITE_API_BASE_URL`. Do not commit credentials; remember that `VITE_` variables are exposed to browser code.
