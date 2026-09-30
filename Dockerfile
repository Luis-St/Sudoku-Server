FROM eclipse-temurin:25-jdk-alpine as builder

RUN jlink \
    --add-modules "java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.rmi,java.scripting,java.security.jgss,java.sql,jdk.unsupported" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output /jre

FROM alpine:latest

RUN apk add --no-cache wget && addgroup -S sudoku && adduser -S -G sudoku sudoku

COPY --from=builder /jre /opt/jre
ENV JAVA_HOME=/opt/jre PATH="/opt/jre/bin:$PATH"

WORKDIR /app
COPY build/libs/Sudoku-Server.jar app.jar

ENV SUDOKU_LOG_LEVEL=WARN

USER sudoku
EXPOSE 7000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
