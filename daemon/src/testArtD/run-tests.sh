#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C.UTF-8
cd "$(dirname "$0")/../../.."
out=$(mktemp -d)
trap 'rm -f "$out"/org/lsposed/lspd/service/*.class; rmdir "$out"/org/lsposed/lspd/service "$out"/org/lsposed/lspd "$out"/org/lsposed "$out"/org "$out"' EXIT
javac -encoding UTF-8 -d "$out" \
    daemon/src/main/java/org/lsposed/lspd/service/ArtDBackend.java \
    daemon/src/main/java/org/lsposed/lspd/service/ArtDReceipt.java \
    daemon/src/testArtD/java/org/lsposed/lspd/service/ArtDBackendTest.java \
    daemon/src/testArtD/java/org/lsposed/lspd/service/ArtDReceiptTest.java
java -cp "$out" org.lsposed.lspd.service.ArtDBackendTest
java -cp "$out" org.lsposed.lspd.service.ArtDReceiptTest
