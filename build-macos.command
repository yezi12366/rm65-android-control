#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
# Prefer the caller's JAVA_HOME; otherwise try the standard Android Studio JBR.
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Java not found. Set JAVA_HOME to JDK 17 or Android Studio's JBR." >&2
  exit 1
fi
if [ "$#" -eq 0 ]; then set -- :app:assembleDebug; fi
exec ./gradlew --console=plain "$@"
