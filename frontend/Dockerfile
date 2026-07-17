# Build context is the repo ROOT, not ./frontend: the Docs page bundles the
# markdown and screenshots from /docs, which lives outside the frontend tree.
# WORKDIR sits one level below the repo root so the relative docs imports in
# src/components/docs/docsContent.ts resolve here exactly as they do on a
# developer machine.
FROM node:22-alpine AS build

WORKDIR /app/frontend

COPY frontend/package*.json ./
# `npm install`, not `npm ci`: the lock is generated on the Windows host, where
# floating wasm build deps (@rolldown/binding-wasm32-wasi → @emnapi) resolve to
# different exact patch versions than this Alpine/Linux build. Strict `npm ci`
# rejects that drift; `npm install` reconciles it.
RUN npm install --no-audit --no-fund

COPY frontend/ ./
COPY docs/ /app/docs/
RUN npm run build

FROM nginx:1.27-alpine

COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/frontend/dist /usr/share/nginx/html

EXPOSE 80
