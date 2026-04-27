FROM gradle:8.10-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar downloadAgent --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/build/agent/dd-java-agent.jar dd-java-agent.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-javaagent:dd-java-agent.jar", \
  "-Ddd.profiling.enabled=true", \
  "-Ddd.logs.injection=true", \
  "-Ddd.trace.sample.rate=1", \
  "-jar", "app.jar"]
