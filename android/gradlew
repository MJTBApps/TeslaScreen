#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE="$ROOT/.gradle-dist/gradle-8.11.1/bin/gradle"
if [ ! -x "$GRADLE" ]; then
    mkdir -p "$ROOT/.gradle-dist"
    echo "Descargando Gradle 8.11.1..."
    curl -fL --retry 2 -o "$ROOT/.gradle-dist/gradle.zip" "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
    (cd "$ROOT/.gradle-dist" && unzip -qo gradle.zip && rm gradle.zip)
fi
exec "$GRADLE" "$@"
