FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
# `npm install`, not `npm ci`: the lock is generated on the Windows host, where
# floating wasm build deps (@rolldown/binding-wasm32-wasi → @emnapi) resolve to
# different exact patch versions than this Alpine/Linux build. Strict `npm ci`
# rejects that drift; `npm install` reconciles it.
RUN npm install --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

