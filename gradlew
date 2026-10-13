#!/bin/sh
# Простой загрузчик Gradle: скачивает Gradle при первом запуске и запускает его.
GRADLE_VERSION=7.6.4
DIR="$(cd "$(dirname "$0")" && pwd)"
CACHE="$DIR/.gradle-dist"
GRADLE_HOME="$CACHE/gradle-$GRADLE_VERSION"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$CACHE"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  echo "Скачиваю Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL -o "$CACHE/gradle.zip" "$URL" || exit 1
  else
    wget -O "$CACHE/gradle.zip" "$URL" || exit 1
  fi
  unzip -q -o "$CACHE/gradle.zip" -d "$CACHE" || exit 1
  rm -f "$CACHE/gradle.zip"
  chmod +x "$GRADLE_HOME/bin/gradle"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
