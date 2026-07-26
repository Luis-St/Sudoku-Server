# Runtime-only image: it expects the fat jar to have been built on the host first.
#
#   ./gradlew shadowJar && docker compose -f compose.yaml -f compose.local.yaml up --build
#
# This is deliberately not a multi-stage build. shared-core (net.luis:sudoku-lib:1.0.0) has never been
# published to the Artifactory (maven.luis-st.net) - it's the owner's private lib - so it only resolves
# from the developer's ~/.m2, which a build stage inside the container cannot reach. (LUtils itself
# fetches fine from the Artifactory; the outage there only blocks publishing new projects, not
# resolving existing ones.) Once shared-core is published, this can become a normal multi-stage build.
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache wget && \
    addgroup -S sudoku && adduser -S -G sudoku sudoku

WORKDIR /app
COPY build/libs/Sudoku-Server-1.0.0.jar app.jar

USER sudoku
EXPOSE 7000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
