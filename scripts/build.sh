#!/usr/bin/env bash
# Local build: native Maven + JDK 17 on this host. No Docker.
#
# This host has no mvn on PATH, so we use the Maven bundled with IntelliJ IDEA, and we
# point JAVA_HOME at a real JDK (the PATH java is a JRE 8 without javac).
#
# NOTE: the Nexus settings.xml declares <localRepository>D:/Users/leler/.m2/repository</localRepository>.
# That is a Windows path and only resolves correctly under a native Windows Maven — it is the
# reason the earlier containerised build produced an empty compile classpath.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-C:/Users/Administrator/.jdks/ms-17.0.20.1}"
MVN="${MVN:-/d/Program Files/JetBrains/IntelliJ IDEA 2026.1.4/plugins/maven-plugin/lib/maven3/bin/mvn.cmd}"
SETTINGS="${SETTINGS:-D:/Users/leler/.m2/settings.xml}"
GOAL="${GOAL:-clean package}"
MVN_EXTRA="${MVN_EXTRA:-}"   # e.g. "-o" once dependencies are cached

echo "==> JAVA_HOME : $JAVA_HOME"
echo "==> mvn       : $MVN"
echo "==> settings  : $SETTINGS"
echo "==> goal      : $GOAL"

if [ ! -x "$JAVA_HOME/bin/javac" ] && [ ! -f "$JAVA_HOME/bin/javac.exe" ]; then
  echo "ERROR: no javac under $JAVA_HOME — set JAVA_HOME to a JDK 17+" >&2
  exit 1
fi

export JAVA_HOME

"$MVN" -s "$SETTINGS" $MVN_EXTRA $GOAL -f pom.xml

echo
echo "==> artifact:"
ls -la ai-agent-app/target/*.jar
