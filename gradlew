#!/bin/sh
# Text-only Gradle bootstrap, including checksum validation.
set -eu
VERSION=8.9
CACHE_ROOT="${GRADLE_USER_HOME:-${HOME}/.gradle}/overlay-bootstrap"
DISTRIBUTION="${CACHE_ROOT}/gradle-${VERSION}"
ARCHIVE="${CACHE_ROOT}/gradle-${VERSION}-bin.zip"
if [ ! -x "${DISTRIBUTION}/bin/gradle" ]; then
    mkdir -p "${CACHE_ROOT}"
    curl --fail --location --retry 3 \
        "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip" \
        --output "${ARCHIVE}"
    curl --fail --location --retry 3 \
        "https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip.sha256" \
        --output "${ARCHIVE}.sha256"
    python3 - "${ARCHIVE}" "${ARCHIVE}.sha256" "${CACHE_ROOT}" <<'PY'
import hashlib
import pathlib
import sys
import zipfile
archive, checksum, destination = map(pathlib.Path, sys.argv[1:])
expected = checksum.read_text().strip().split()[0]
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit("Gradle distribution checksum mismatch")
with zipfile.ZipFile(archive) as source:
    root = destination.resolve()
    for entry in source.infolist():
        if not (root / entry.filename).resolve().is_relative_to(root):
            raise SystemExit("Unsafe distribution entry")
    source.extractall(root)
PY
    chmod +x "${DISTRIBUTION}/bin/gradle"
fi
exec "${DISTRIBUTION}/bin/gradle" "$@"
