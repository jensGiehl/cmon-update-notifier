# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM eclipse-temurin:27-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:27-jdk
WORKDIR /app
RUN groupadd --gid 10001 cmon \
    && useradd --uid 10001 --gid cmon --no-create-home --shell /usr/sbin/nologin cmon \
    && mkdir /data \
    && chown cmon:cmon /data
COPY --from=build --chown=cmon:cmon /build/target/cmon-update.jar /app/cmon-update.jar
USER 10001:10001
ENV CMON_DATABASE_PATH=/data/cmon-state
VOLUME ["/data"]
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", "/app/cmon-update.jar"]
