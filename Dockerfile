# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so a source change does not re-download the world.
COPY pom.xml .
RUN mvn -B dependency:go-offline || true

COPY src ./src
RUN mvn -B package

# ---- run ----
# A JDK, not a JRE, and this is not optional. Two things here need the
# jdk.compiler module, which Temurin's JRE image does not ship:
#   - the grading compile check, via javax.tools.ToolProvider
#   - JPlag's Java parser, which is built on javac
# On a JRE, getSystemJavaCompiler() returns null and JPlag fails to parse.
FROM eclipse-temurin:21-jdk
RUN useradd -r -u 10001 -m -d /home/app app
WORKDIR /app

COPY --from=build /build/target/codetriage-0.1.0.jar /app/app.jar
RUN mkdir -p /var/lib/codetriage && chown -R app:app /var/lib/codetriage /app

USER app
ENV CODETRIAGE_DATA_DIR=/var/lib/codetriage
EXPOSE 8080

# A triage run holds every submission's token stream in memory at once.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
