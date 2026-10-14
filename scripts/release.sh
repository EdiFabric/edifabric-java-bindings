#!/usr/bin/env bash
# Build the jar with the four bundled libraries, smoke-test it, and upload it to Maven Central.
#
# Needs Maven 3.9+, JDK 11+ (for the smoke test), and for a real release gpg with the signing key
# plus a "central" server in ~/.m2/settings.xml holding a Central Portal user token.
#
#   scripts/release.sh --dry-run        build and smoke-test without signing or uploading
#   scripts/release.sh                  upload; publish from the Central Portal afterwards
#   scripts/release.sh --auto-publish   upload and publish without the manual step
set -euo pipefail
cd "$(dirname "$0")/.."

dry_run=false skip_fetch=false auto_publish=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=true ;;
        --skip-fetch) skip_fetch=true ;;
        --auto-publish) auto_publish=true ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
mvn=${MAVEN:-mvn}

$skip_fetch || bash scripts/fetch-natives.sh natives

if $dry_run; then sign=-Dgpg.skip; else sign=-Dgpg.skip=false; fi
"$mvn" -B -ntp -Prelease "$sign" clean verify
"$mvn" -B -ntp -q dependency:copy-dependencies -DincludeScope=runtime -DexcludeArtifactIds=json -DoutputDirectory=target/deps

jar=$(ls target/edifabric-x12-native-*.jar | grep -Ev -- '-(sources|javadoc)\.jar$' | head -n 1)
smoke=$(mktemp -d)
trap 'rm -rf "$smoke"' EXIT
cp "$jar" target/deps/*.jar scripts/SmokeTest.java "$smoke"
(cd "$smoke" && java -cp "$(ls *.jar | paste -sd: -)" SmokeTest.java)

if $dry_run; then
    echo "Dry run: built $(basename "$jar"), nothing signed or uploaded."
    exit 0
fi
"$mvn" -B -ntp -Prelease -Dcentral.autoPublish="$auto_publish" deploy
