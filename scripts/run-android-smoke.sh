#!/usr/bin/env bash
# Run the instrumented tests with a bounded timeout and capture a thread dump if they hang.
set -o pipefail
(sleep 50; pid=$(adb shell pidof com.tanasi.streamflix.debug | tr -d '\r'); if [ -n "$pid" ]; then adb shell kill -3 "$pid"; fi) &
dump_pid=$!
timeout 240s ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace 2>&1 | tee -a "$RUNNER_TEMP/android.log"
result=${PIPESTATUS[0]}
kill "$dump_pid" 2>/dev/null || true
if [ "$result" -ne 0 ]; then
  adb logcat -d -v brief | grep -E 'python|Python|Chaquopy|FATAL|AndroidRuntime|streamflix' | tail -300 >> "$RUNNER_TEMP/android.log" || true
  adb root || true
  adb shell 'for f in /data/anr/*; do cat "$f"; done' 2>/dev/null | tail -500 >> "$RUNNER_TEMP/android.log" || true
fi
exit "$result"
