FROM node:20-slim AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY tsconfig.json ./
COPY src ./src
RUN npx tsc

FROM node:20-slim
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY --from=build /app/dist ./dist

EXPOSE 3000

CMD ["node", "--require", "dd-trace/init", "dist/index.js"]
