# ── Build stage ────────────────────────────────────────────────────────────────
FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN apt-get update -qq && apt-get install -y -qq maven \
 && mvn package -q -DskipTests \
 && mv target/iap-agent-*.jar target/iap-agent.jar

# ── Runtime stage ───────────────────────────────────────────────────────────────
FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu

# Utilisateur non-root
RUN useradd -r -u 1000 -g root appuser
USER appuser

WORKDIR /app
COPY --from=build /build/target/iap-agent.jar iap-agent.jar

ENTRYPOINT ["java", "-jar", "iap-agent.jar"]
