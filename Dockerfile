FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app/backend
COPY backend/ ./
COPY --from=frontend-build /app/frontend/dist/ ./src/main/resources/static/
RUN mvn clean package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /app/backend/target/smartlib-backend-1.0.0.jar app.jar
EXPOSE 10000
CMD ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar app.jar"]
