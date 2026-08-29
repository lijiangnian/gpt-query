#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.test-out"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/app/src/main/java/com/example/mediaparser/core/LinkExtractor.java" \
  "$ROOT/app/src/test/java/com/example/mediaparser/core/LinkExtractorSmokeTest.java"
java -cp "$OUT" com.example.mediaparser.core.LinkExtractorSmokeTest
