#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Dash0 Inc.
# SPDX-License-Identifier: Apache-2.0
#
# Runs the compose stack and asserts on the telemetry the collector received.
#
# Requires docker, docker compose and jq. Build the agent first:
#
#     ./gradlew :agent:shadowJar
#     examples/dash0-distro-java/verify.sh

set -euo pipefail

cd "$(dirname "$0")"

SERVICE=dash0-distro-java-demo
PACKAGED_SERVICE=dash0-distro-java-packaged-demo

if ! compgen -G '../../agent/build/libs/dash0-opentelemetry-javaagent-*.jar' > /dev/null; then
  echo "No agent jar found. Run ./gradlew :agent:shadowJar first." >&2
  exit 1
fi

SCRIPT_UID="$(id -u)"
SCRIPT_GID="$(id -g)"
export SCRIPT_UID SCRIPT_GID

cleanup() {
  docker compose down --timeout 10 --remove-orphans > /dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup
rm -rf output
mkdir -p output

docker compose up --detach

# `|| true` is required: on an already-exited container, wait returns 1 and
# `set -e` would end the script before the diagnostics below.
docker compose wait app app-packaged > /dev/null || true

for service in app app-packaged; do
  read -r state exit_code \
    <<< "$(docker compose ps --all --format '{{.State}} {{.ExitCode}}' "$service")" || true

  if [ -z "$state" ]; then
    docker compose logs
    echo "$service has no container; it was never created." >&2
    exit 1
  elif [ "$state" != "exited" ]; then
    docker compose logs
    echo "$service did not finish; it is $state." >&2
    exit 1
  elif [ "$exit_code" != "0" ]; then
    docker compose logs
    echo "$service exited with status $exit_code." >&2
    exit 1
  fi
done

sleep 3
docker compose down --timeout 10 > /dev/null

SPANS_OF_KIND='
  [ .[] | .resourceSpans[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .scopeSpans[].spans[]
    | select(.kind == $kind) ] | length > 0
'

NAMED_SPAN='
  [ .[] | .resourceSpans[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .scopeSpans[].spans[]
    | select(.name == $name) ] | length > 0
'

RESOURCE_ATTRIBUTE='
  [ .[]
    | (.resourceSpans // .resourceMetrics // .resourceLogs)[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .resource.attributes[]
    | select(.key == $key and .value.stringValue == $value) ] | length > 0
'

NAMED_METRIC='
  [ .[] | .resourceMetrics[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .scopeMetrics[].metrics[]
    | select(.name == $name) ] | length > 0
'

PROPAGATION='
  [ .[] | .resourceSpans[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .scopeSpans[].spans[] ] as $spans
  | [ $spans[] | select(.kind == 2) ] as $servers
  | [ $spans[] | select(.kind == 3) ] as $clients
  | ($servers | length) > 0
    and any($servers[]; . as $server
        | any($clients[]; .spanId == $server.parentSpanId
            and .traceId == $server.traceId))
'

LOG_BODY='
  [ .[] | .resourceLogs[]
    | select(any(.resource.attributes[];
        .key == "service.name" and .value.stringValue == $svc))
    | .scopeLogs[].logRecords[]
    | select(.body.stringValue | test($substring)) ] | length > 0
'

failures=0

assert() {
  local description="$1" file="$2"
  shift 2
  if [ ! -s "$file" ]; then
    printf '  FAIL  %s (%s is missing or empty)\n' "$description" "$file"
    failures=$((failures + 1))
    return
  fi
  if jq --exit-status --slurp "$@" "$file" > /dev/null; then
    printf '  ok    %s\n' "$description"
  else
    printf '  FAIL  %s\n' "$description"
    failures=$((failures + 1))
  fi
}

echo
echo "Traces"
assert "a SERVER span arrived, so com.sun.net.httpserver was instrumented" \
  output/traces.jsonl --arg svc "$SERVICE" --argjson kind 2 "$SPANS_OF_KIND"
assert "a CLIENT span arrived, so HttpURLConnection was instrumented" \
  output/traces.jsonl --arg svc "$SERVICE" --argjson kind 3 "$SPANS_OF_KIND"
assert "the server span is named after the route" \
  output/traces.jsonl --arg svc "$SERVICE" --arg name "GET /items" "$NAMED_SPAN"
assert "spans carry telemetry.distro.name=dash0-java" \
  output/traces.jsonl --arg svc "$SERVICE" \
  --arg key telemetry.distro.name --arg value dash0-java "$RESOURCE_ATTRIBUTE"
assert "trace context propagated, so the client span is the server span's parent" \
  output/traces.jsonl --arg svc "$SERVICE" "$PROPAGATION"
assert "spans do not carry the upstream distro name" \
  output/traces.jsonl --arg svc "$SERVICE" \
  --arg key telemetry.distro.name --arg value opentelemetry-java-instrumentation \
  "${RESOURCE_ATTRIBUTE/length > 0/length == 0}"

echo
echo "Metrics"
assert "JVM memory metrics arrived" \
  output/metrics.jsonl --arg svc "$SERVICE" --arg name jvm.memory.used "$NAMED_METRIC"
assert "HTTP server duration metrics arrived" \
  output/metrics.jsonl --arg svc "$SERVICE" \
  --arg name http.server.request.duration "$NAMED_METRIC"

echo
echo "Logs"
assert "application log records arrived" \
  output/logs.jsonl --arg svc "$SERVICE" --arg substring "fetched item" "$LOG_BODY"

echo
echo "Package layout"
assert "$PACKAGED_SERVICE reports dash0.package.name" \
  output/traces.jsonl --arg svc "$PACKAGED_SERVICE" \
  --arg key dash0.package.name --arg value dash0-java-autoinstrumentation \
  "$RESOURCE_ATTRIBUTE"
assert "$PACKAGED_SERVICE reports dash0.package.version" \
  output/traces.jsonl --arg svc "$PACKAGED_SERVICE" \
  --arg key dash0.package.version --arg value 0.0.0-example "$RESOURCE_ATTRIBUTE"
assert "$SERVICE reports no package attributes, having no properties file" \
  output/traces.jsonl --arg svc "$SERVICE" \
  --arg key dash0.package.name --arg value dash0-java-autoinstrumentation \
  "${RESOURCE_ATTRIBUTE/length > 0/length == 0}"

echo
if [ "$failures" -ne 0 ]; then
  echo "$failures assertion(s) failed."
  exit 1
fi
echo "All assertions passed."
