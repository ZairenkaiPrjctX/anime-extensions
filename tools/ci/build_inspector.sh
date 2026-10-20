#!/usr/bin/env bash
# Builds komikku-app/aniyomi-extensions-inspector from a pinned, verified commit and copies the
# jar to $1. The inspector loads extension APKs on a plain JVM and reports each source's
# name/lang/id/baseUrl, which the catalog needs (ids are computed by the source class itself).
#
# Usage: tools/ci/build_inspector.sh <output.jar> [workdir]
set -euo pipefail

INSPECTOR_REPO="https://github.com/komikku-app/aniyomi-extensions-inspector.git"
INSPECTOR_TAG="v1.5.0.2"
INSPECTOR_COMMIT="85971b3771ce2a260ad42db2ae8ea24a649a7be5"

output="$(realpath -m "$1")"
workdir="${2:-$(mktemp -d)}"

git clone --quiet --depth 1 --branch "$INSPECTOR_TAG" "$INSPECTOR_REPO" "$workdir/inspector"
actual="$(git -C "$workdir/inspector" rev-parse HEAD)"
if [[ "$actual" != "$INSPECTOR_COMMIT" ]]; then
  echo "error: inspector tag $INSPECTOR_TAG resolves to $actual, expected $INSPECTOR_COMMIT" >&2
  exit 1
fi

cd "$workdir/inspector"
./AndroidCompat/getAndroid.sh > /dev/null
# Optional extra Gradle arguments (e.g. local --init-script mirrors), whitespace-separated.
read -r -a extra_args <<< "${INSPECTOR_GRADLE_ARGS:-}"
./gradlew --quiet --no-daemon "${extra_args[@]}" :inspector:shadowJar
jar="$(find inspector/build -maxdepth 1 -name '*.jar' -print -quit)"
if [[ -z "$jar" ]]; then
  echo "error: inspector jar was not produced" >&2
  exit 1
fi
cp "$jar" "$output"
echo "Inspector $INSPECTOR_TAG ($INSPECTOR_COMMIT) -> $output"
