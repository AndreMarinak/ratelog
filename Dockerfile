FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# Pinned to an exact runtime tag: a floating tag can be served from an old cached copy on rebuilds.
# Bump it deliberately (check the Temurin tags on Docker Hub).
FROM eclipse-temurin:21.0.13.1_2-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
