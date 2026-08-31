FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean verify

FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home --home-dir /app gateway
WORKDIR /app
COPY --from=build /workspace/target/mingqian-video-gateway-1.0.0.jar /app/app.jar
RUN mkdir -p /app/data && chown -R gateway:gateway /app
USER gateway
EXPOSE 18080/tcp 5060/udp
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD curl -fsS http://127.0.0.1:18080/login.html || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
