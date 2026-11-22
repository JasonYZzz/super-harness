ARG JAVA_IMAGE=eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
FROM ${JAVA_IMAGE}
ARG ROLE
ARG VERSION=0.1.0-SNAPSHOT
WORKDIR /app
COPY apps/sh-${ROLE}/target/sh-${ROLE}-${VERSION}.jar /app/service.jar
USER 65532:65532
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/service.jar"]
