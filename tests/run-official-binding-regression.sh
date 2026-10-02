#!/bin/sh
# 需先以 JDK25 构建 :folia:26.1.2:shadowJar；不使用真实账号或生产数据库。
set -eu
cd "$(dirname "$0")/.."
JAR=folia/versions/26.1.2/build/libs/luoos-folia-mc26.1.2-0.10.jar
if [ ! -f "$JAR" ]; then
    printf '%s\n' '请先构建 :folia:26.1.2:shadowJar。' >&2
    exit 1
fi
mkdir -p build/binding-regression
javac -cp "$JAR" -d build/binding-regression \
    folia/src/main/java/heos/folia/storage/OfficialBindingRepository.java \
    tests/OfficialBindingRegression.java
java --enable-native-access=ALL-UNNAMED \
    -cp "build/binding-regression:$JAR" OfficialBindingRegression
