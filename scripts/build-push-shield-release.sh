#!/usr/bin/env bash
#
# build-push-shield-release.sh: build the Android TV APK (via nix-shell) and
# install it on a Shield over ADB.
#
# Default is the debug variant: no minification, debug-keystore signed
# automatically. With --release, assembles the release variant signed with the
# debug keystore via -PallowDebugReleaseSigning=true so it installs locally
# without play signing. Neither variant needs a zipalign/apksigner step.
#
# Usage:
#   bash scripts/build-push-shield-release.sh                 # debug build + install
#   bash scripts/build-push-shield-release.sh -r              # release build + install
#   bash scripts/build-push-shield-release.sh -d              # allow versionCode downgrade
#   bash scripts/build-push-shield-release.sh --skip-build    # install existing APK
#   bash scripts/build-push-shield-release.sh -r --skip-build # install existing release APK
#   SHIELD_HOST=192.0.2.10 bash scripts/build-push-shield-release.sh
#
# The Shield address defaults to SHIELD_HOST/SHIELD_PORT from .env at the repo
# root (gitignored; see .env.example for the tracked template). --host wins.
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

# Local environment overrides (gitignored): SHIELD_HOST, SHIELD_PORT. See
# .env.example. Precedence: --host flag > process environment > .env.
if [[ -f "$REPO_ROOT/.env" ]]; then
    while IFS='=' read -r key value; do
        [[ -z "$key" || "$key" == \#* || -z "$value" ]] && continue
        [[ -n "${!key:-}" ]] && continue
        export "$key=$value"
    done < "$REPO_ROOT/.env"
fi

HOST=${SHIELD_HOST:-}
PORT=${SHIELD_PORT:-5555}
RELEASE=0
SKIP_BUILD=0
ALLOW_DOWNGRADE=0
VARIANT="debug"
APP_ID="org.siloserver.silo"

log() { printf "\n\033[1;34m[tv-release]\033[0m %s\n" "$*"; }
die() { printf "\n\033[1;31m[tv-release] ERROR:\033[0m %s\n" "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST=${2:?"--host requires a value"}; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        -d|--allow-downgrade) ALLOW_DOWNGRADE=1; shift ;;
        -r|--release)
            RELEASE=1
            VARIANT="release"
            shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

[[ -n "$HOST" ]] || die "No Shield host. Set SHIELD_HOST in $REPO_ROOT/.env (see .env.example) or pass --host <address>."

if [[ "$RELEASE" -eq 1 ]]; then
    APK="$REPO_ROOT/androidTvApp/build/outputs/apk/release/androidTvApp-arm64-v8a-release.apk"
    GRADLE_TASK=":androidTvApp:assembleRelease"
    GRADLE_ARGS=(-PallowDebugReleaseSigning=true)
else
    APK="$REPO_ROOT/androidTvApp/build/outputs/apk/debug/androidTvApp-arm64-v8a-debug.apk"
    GRADLE_TASK=":androidTvApp:assembleDebug"
    GRADLE_ARGS=()
fi

command -v nix-shell >/dev/null || die "nix-shell not found in PATH."
command -v adb >/dev/null || die "adb not found in PATH."

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    if git -C "$REPO_ROOT" rev-parse --abbrev-ref '@{upstream}' >/dev/null 2>&1; then
        log "Pulling latest"
        git -C "$REPO_ROOT" pull --ff-only || die "git pull --ff-only failed. Resolve manually."
    else
        log "No upstream for current branch; skipping pull"
    fi

    log "Building $GRADLE_TASK via nix-shell"
    (
        cd "$REPO_ROOT"
        NIXPKGS_ALLOW_UNFREE=1 nix-shell --run "./gradlew $GRADLE_TASK ${GRADLE_ARGS[*]} --no-daemon -Dorg.gradle.jvmargs=\"-Xmx6g -Dfile.encoding=UTF-8\""
    )
else
    log "Skipping build; reusing existing APK"
fi

[[ -f "$APK" ]] || die "APK not found at $APK"

SERIAL="${HOST}:${PORT}"
log "Connecting to $SERIAL"
adb start-server >/dev/null
if ! adb devices | awk 'NR>1 {print $1}' | grep -Fxq "$SERIAL"; then
    adb connect "$SERIAL" >/dev/null || true
fi
adb devices | awk 'NR>1 {print $1}' | grep -Fxq "$SERIAL" \
    || die "Could not reach $SERIAL. Check the Shield is awake with network debugging on."

INSTALL_ARGS=(-r)
if [[ "$ALLOW_DOWNGRADE" -eq 1 ]]; then
    INSTALL_ARGS+=(-d)
    log "Installing $APK (allow versionCode downgrade)"
else
    log "Installing $APK"
fi
adb -s "$SERIAL" install "${INSTALL_ARGS[@]}" "$APK"

adb -s "$SERIAL" shell pm list packages | grep -Fq "$APP_ID" \
    || die "Install finished, but $APP_ID was not reported by pm list packages."

log "Done"
printf "Installed %s (%s) on %s\n" "$APP_ID" "$VARIANT" "$SERIAL"
