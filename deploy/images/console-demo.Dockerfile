# Local demo image only; production deployments should serve the built assets
# from an audited edge/static server with their final CSP.
FROM node:26.3.0-alpine3.22
WORKDIR /app
ARG VITE_DEMO_AUTH=true
ENV VITE_DEMO_AUTH=${VITE_DEMO_AUTH}
COPY console/package.json console/package-lock.json ./
RUN npm ci --ignore-scripts
COPY console ./
RUN npm run build
ENV SMARTDATA_API_ORIGIN=http://runtime-api:8080
USER node
EXPOSE 4173
CMD ["npm", "run", "preview", "--", "--host", "0.0.0.0", "--port", "4173"]
