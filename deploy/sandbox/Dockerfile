ARG JAVA_IMAGE=eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d
FROM ${JAVA_IMAGE}
RUN useradd --uid 65532 --no-create-home --shell /usr/sbin/nologin runner && mkdir -p /workspace && chown runner:runner /workspace
COPY modules/sh-runtime-agentscope/target/classes/io/superharness/runtime/SandboxEdit.class /opt/tools/io/superharness/runtime/SandboxEdit.class
USER 65532:65532
WORKDIR /workspace
CMD ["sleep","infinity"]
