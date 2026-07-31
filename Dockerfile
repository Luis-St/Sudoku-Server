FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache wget && addgroup -S sudoku && adduser -S -G sudoku sudoku

WORKDIR /app
COPY build/libs/Sudoku-Server-1.0.0.jar app.jar

USER sudoku
EXPOSE 7000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
