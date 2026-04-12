#!/usr/bin/env bash

set -eou pipefail

# Determine version: git tag takes priority, fall back to GITHUB_REF (e.g. refs/tags/v0.6.9)
if APP_VERSION=$(git describe --tags --abbrev=0 2>/dev/null); then
    APP_VERSION="${APP_VERSION#v}"
elif [[ -n "${GITHUB_REF:-}" ]]; then
    _ref="${GITHUB_REF#refs/tags/}"
    APP_VERSION="${_ref#v}"
else
    echo "Error: could not determine version (no git tags and GITHUB_REF is unset)" >&2
    exit 1
fi

echo "Building version: ${APP_VERSION}"

GRADLE_OPTS=(--no-daemon --console=plain -Pversion="${APP_VERSION}" build)

# In the flatpak-builder sandbox FLATPAK_ID is set; use the system gradle binary and --offline
if [[ -n "${FLATPAK_ID:-}" ]]; then
    gradle --offline "${GRADLE_OPTS[@]}"
else
    ./gradlew "${GRADLE_OPTS[@]}"
fi
