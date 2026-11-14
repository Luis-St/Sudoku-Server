#!/bin/sh

read -p "Version (x.x.x): " version

./gradlew shadowJar
docker build -t "sudoku-server:$version" .
