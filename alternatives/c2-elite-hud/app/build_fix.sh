#!/usr/bin/env bash
# Build with higher memory limit for JVM
# Override task-runner's default 1GB virtual memory limit
ulimit -v 4194304 2>/dev/null || true
export JAVA_OPTS="-Xmx512m"
bash "$(dirname "$0")/build.sh"
