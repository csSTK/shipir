# syntax=docker/dockerfile:1

FROM node:24-alpine AS build
WORKDIR /app
# pnpm version is taken from "packageManager" in package.json
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginxinc/nginx-unprivileged:stable-alpine
# envsubst renders /etc/nginx/templates/*.template into /etc/nginx/conf.d at start
ENV BACKEND_URL=http://backend:8080
# Docker's embedded DNS; override outside Docker
ENV NGINX_RESOLVER=127.0.0.11
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
