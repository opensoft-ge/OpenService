FROM eclipse-temurin:27-jdk@sha256:5e6d9093c7a8e95adfaa8828253c58f2c6820116d07a062440a778358807578a AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src/main src/main
COPY LICENSE NOTICE THIRD_PARTY_NOTICES.md ./
COPY licenses licenses
COPY docs/runtime-licenses.json docs/provenance.md docs/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS runtime
# OpenService launches Java directly; the inherited Go service manager is unused.
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl && rm -f /usr/bin/pebble && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app /data && chown -R 10001:10001 /app /data
WORKDIR /app
COPY --from=build /build/target/openservice-0.2.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV DATABASE_URL=sqlite:////data/openservice.db
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD curl --fail --silent http://localhost:8080/readyz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
