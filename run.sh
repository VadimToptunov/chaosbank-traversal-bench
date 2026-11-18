#!/bin/sh
# Usage: ./run.sh once B | speed 6 | honesty   (pass -D settings via JAVA_OPTS)
cd "$(dirname "$0")"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt >/dev/null || exit 1
exec java $JAVA_OPTS -cp "target/classes:$(cat target/cp.txt)" bench.Bench "$@"
