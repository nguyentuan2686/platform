# Build context: repo root.  docker build -f services/web/Dockerfile -t web:local .
FROM node:22-alpine AS build
WORKDIR /src
ENV NEXT_TELEMETRY_DISABLED=1
COPY services/web/package.json services/web/package-lock.json ./
RUN npm ci
COPY services/web/ ./
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /src/.next/standalone ./
COPY --from=build --chown=node:node /src/.next/static ./.next/static
USER node
EXPOSE 3000
# No HEALTHCHECK: orchestrator probes /health/live and /health/ready.
CMD ["node", "server.js"]
