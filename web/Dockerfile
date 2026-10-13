FROM node:24.19.0-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY angular.json tsconfig*.json ./
COPY src ./src
RUN npm run build

FROM caddy:2.11.4-alpine
ENV XDG_CONFIG_HOME=/tmp/config XDG_DATA_HOME=/tmp/data
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist/armoney/browser /srv
EXPOSE 8080
