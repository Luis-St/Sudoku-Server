FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache wget && addgroup -S sudoku && adduser -S -G sudoku sudoku

WORKDIR /app
COPY build/libs/Sudoku-Server.jar app.jar

ENV SUDOKU_LOG_LEVEL=WARN

USER sudoku
EXPOSE 7000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
