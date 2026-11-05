# 1) 두 커널 빌드: kernel/kernel + fs.img
FROM ubuntu:24.04 AS kernels
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential perl \
 && rm -rf /var/lib/apt/lists/*
COPY os/rx64-riscv-style /opt/os/rx64-riscv-style
COPY os/rx64-x86-style /opt/os/rx64-x86-style
RUN make -C /opt/os/rx64-riscv-style kernel/kernel fs.img \
 && make -C /opt/os/rx64-x86-style kernel/kernel fs.img

# 2) 서버 jar 빌드
FROM eclipse-temurin:25-jdk AS app
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# 3) 실행: JRE + QEMU + gdb(Python 포함)
FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends qemu-system-x86 gdb \
 && rm -rf /var/lib/apt/lists/*
COPY --from=kernels /opt/os /opt/os
COPY --from=app /src/target/xv6-viz-*.jar /opt/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/app.jar", \
  "--server.address=0.0.0.0", \
  "--xv6viz.kernels.riscv=/opt/os/rx64-riscv-style", \
  "--xv6viz.kernels.x86=/opt/os/rx64-x86-style"]
