FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# Pinned to an exact runtime tag: a floating tag can be served from an old cached copy on rebuilds.
# Bump it deliberately (check the Temurin tags on Docker Hub).
FROM eclipse-temurin:21.0.13.1_2-jre-alpine

# Run as an unprivileged user, not root. The app only writes to /tmp (Tomcat work files, upload buffering).
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV PORT=8080

EXPOSE 8080

USER 10001:10001

ENTRYPOINT ["java", "-jar", "app.jar"]
