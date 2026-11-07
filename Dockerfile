# Build and trace environment for the two xv6 x86-64 ports in os/, plus
# the visualizer server. os/ is copied in and built here; the sources on
# the host stay untouched.

# ---- server (Spring Boot) ----
FROM gradle:9.8.2-jdk25-noble AS server
ENV GRADLE_USER_HOME=/cache/gradle
WORKDIR /src
COPY server/settings.gradle.kts server/build.gradle.kts ./
RUN --mount=type=cache,target=/cache/gradle gradle --no-daemon -q dependencies > /dev/null
COPY server/src ./src
RUN --mount=type=cache,target=/cache/gradle \
    gradle --no-daemon -q bootJar -x test && cp build/libs/rxviz-server-0.1.0.jar /server.jar

# ---- web UI (TypeScript + Vite) ----
FROM node:24.22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- runtime: QEMU, kernels, plugin, server ----
FROM ubuntu:26.04

ARG QEMU_TAG=v10.2.1

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential perl python3 curl ca-certificates \
        qemu-system-x86 libglib2.0-dev pkg-config zstd \
        openjdk-25-jre-headless \
    && rm -rf /var/lib/apt/lists/*

# QEMU ships no plugin header package; take it from the matching source tag.
RUN mkdir -p /opt/qemu-plugin/include/qemu \
    && curl -fsSL "https://gitlab.com/qemu-project/qemu/-/raw/${QEMU_TAG}/include/qemu/qemu-plugin.h" \
        -o /opt/qemu-plugin/include/qemu/qemu-plugin.h

WORKDIR /work

# Kernels: copy each tree, add the extra user programs, build kernel + fs.img.
COPY os /src/os
COPY extra /src/extra
COPY tracer/build-kernels.sh /src/tracer/build-kernels.sh
RUN /src/tracer/build-kernels.sh /src/os /src/extra /work/kernels

# Trace plugin.
COPY tracer/plugin /src/tracer/plugin
RUN make -C /src/tracer/plugin QEMU_PLUGIN_INC=/opt/qemu-plugin/include OUT=/work/plugin

COPY tracer/*.py /work/tools/
COPY --from=server /server.jar /work/server.jar
COPY --from=web /web/dist /work/web

VOLUME /data
EXPOSE 8080
# G1 returns unused heap to the OS after a few idle seconds
CMD ["java", "-Xmx2g", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=5000", \
     "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30", "-jar", "/work/server.jar"]
