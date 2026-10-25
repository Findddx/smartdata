# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25.0.4_7-jdk-noble AS build
# The patched 25.0.4.1+1 binaries are layered from the official Adoptium
# release with immutable checksums until equivalent Docker tags are published.
ADD --checksum=sha256:dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz /tmp/temurin-jdk.tar.gz
RUN mkdir -p /opt/temurin-pinned && tar -xzf /tmp/temurin-jdk.tar.gz -C /opt/temurin-pinned --strip-components=1 && \
    rm /tmp/temurin-jdk.tar.gz && /opt/temurin-pinned/bin/java -version
ENV JAVA_HOME=/opt/temurin-pinned
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY foundation-kernel ./foundation-kernel
COPY domain-compiler ./domain-compiler
COPY runtime-api ./runtime-api
COPY workflow-worker ./workflow-worker
RUN chmod 0755 gradlew
ARG MODULE
RUN test "${MODULE}" = "runtime-api" -o "${MODULE}" = "workflow-worker"
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --stacktrace ":${MODULE}:bootJar" && \
    JAR="$(find "${MODULE}/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" && \
    test -n "${JAR}" && cp "${JAR}" /workspace/app.jar

FROM eclipse-temurin:25.0.4_7-jre-noble
ADD --checksum=sha256:1731a34baadec5479258ea0202e4d5d865d2efeee60cb0c7d7eb056fe96ca219 https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jre_x64_linux_hotspot_25.0.4.1_1.tar.gz /tmp/temurin-jre.tar.gz
RUN mkdir -p /opt/temurin-pinned && tar -xzf /tmp/temurin-jre.tar.gz -C /opt/temurin-pinned --strip-components=1 && \
    rm /tmp/temurin-jre.tar.gz && /opt/temurin-pinned/bin/java -version
ENV JAVA_HOME=/opt/temurin-pinned
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN groupadd --system --gid 10001 smartdata && useradd --system --uid 10001 --gid smartdata --home-dir /nonexistent --shell /usr/sbin/nologin smartdata
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
