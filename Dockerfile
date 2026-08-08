# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies resolve in their own layer, keyed only on the pom. Source
# changes -- which is nearly every build -- then reuse the cached download
# instead of re-fetching the whole dependency tree.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests need Docker (Testcontainers) and are run by CI before this image is
# ever built. Running them here would need Docker inside Docker to prove
# something already proven.
RUN mvn -B -q clean package -DskipTests

# -------------------------------------------------------------- runtime ----
# jammy, not alpine. Temurin publishes its Alpine images for x86-64 only, so
# `-jre-alpine` builds fine on a CI runner and fails outright on an Apple
# Silicon laptop -- a base image you cannot build on your own machine is the
# wrong base image, whatever it saves. Costs roughly 80MB.
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl for the healthcheck below; Ubuntu's slim JRE image does not ship one.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Never root. A ledger process has no business being able to write to its own
# image, and this costs nothing.
RUN groupadd --system obol && useradd --system --gid obol --create-home obol
USER obol

WORKDIR /app
COPY --from=build --chown=obol:obol /build/target/obol-ledger-*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: free-tier hosts hand out anywhere
# from 256MB to 1GB and the JVM's own default heuristics are far too
# conservative in a container. 70% leaves room for metaspace, thread stacks and
# the JVM's own overhead within the cgroup limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

# UseSerialGC and TieredStopAtLevel=1 are deliberate small-instance choices:
# on a single shared vCPU, G1's background threads and the C2 compiler cost
# more than they return. Drop both on anything larger.

# start-period is generous on purpose: a JVM cold-starting on a shared vCPU
# takes far longer than the steady-state check suggests, and a short grace
# window just restarts a container that was booting normally.
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
