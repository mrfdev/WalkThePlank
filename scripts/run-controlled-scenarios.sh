#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd "$script_dir/.." && pwd -P)"
invocation_dir="$PWD"

default_java="/Library/Java/JavaVirtualMachines/jdk-25.0.2.jdk/Contents/Home/bin/java"
default_paper="$project_dir/servers/Paper-26.2/Paper-26.2.jar"
default_placeholderapi="$project_dir/servers/Paper-26.2/plugins/PlaceholderAPI-2.12.3.jar"
runtime_root="$project_dir/build/controlled-scenarios"
runner_lock="$runtime_root/.runner-lock"
artifact_dir="$project_dir/build/scenario-artifacts"
profile_dir="$project_dir/src/scenarioHarness/profile"
profile_marker=".walktheplank-disposable-profile"
profile_nonce="runner-$(date +%Y%m%d%H%M%S)-$$"

java_bin="${JAVA_BIN:-$default_java}"
paper_jar="$default_paper"
paper_was_set=false
placeholderapi_jar="$default_placeholderapi"
placeholderapi_was_set=false
skip_build=false
player_assisted=false
wait_for_player=false
failpoint_name=""
failpoint_occurrence=1
trigger_command=""
failpoint_timeout="${SCENARIO_FAILPOINT_TIMEOUT:-600}"

server_pid=""
server_port=""
server_fifo=""
server_fifo_open=false
server_raw_log=""
server_clean_log=""
lock_acquired=false

usage() {
    cat <<'USAGE'
Usage:
  scripts/run-controlled-scenarios.sh [PAPER_JAR] [options]
  scripts/run-controlled-scenarios.sh --paper PAPER_JAR [options]

Modes:
  (default)                     Run two automated starts: PlaceholderAPI absent,
                                then present, with restart, reload, and terminal-disable assertions.
  --player-assisted             Start an interactive real-client profile. No GUI,
                                teleport, reconnect, or packet result is synthesized.
  --failpoint NAME              Arm a named HALT failpoint and require exit 97,
                                then restart the same profile without the failpoint.

Failpoint options:
  --occurrence NUMBER           Halt on this hit (default: 1).
  --trigger-command COMMAND     Send one console command after startup.
  --wait-for-player             Wait for a real player to reach the failpoint.
  --failpoint-timeout SECONDS   Maximum wait for exit 97 (default: 600).

General options:
  --paper PAPER_JAR             Paper 26.2 server JAR. The positional form also works.
  --placeholderapi JAR          Deployable PlaceholderAPI server plugin. Defaults to:
                                servers/Paper-26.2/plugins/PlaceholderAPI-2.12.3.jar
  --skip-build                  Reuse existing build/scenario-artifacts.
  -h, --help                    Show this help.

Environment:
  JAVA_BIN                      Java executable. Defaults to the exact Java 25 path:
                                /Library/Java/JavaVirtualMachines/jdk-25.0.2.jdk/Contents/Home/bin/java
  SCENARIO_FAILPOINT_TIMEOUT    Default failpoint wait in seconds.

Examples:
  scripts/run-controlled-scenarios.sh
  scripts/run-controlled-scenarios.sh /path/to/Paper-26.2.jar
  scripts/run-controlled-scenarios.sh --player-assisted
  scripts/run-controlled-scenarios.sh --failpoint config.after_runtime_commit
  scripts/run-controlled-scenarios.sh \
    --failpoint teleport.after_start \
    --wait-for-player
USAGE
}

fail() {
    printf 'CONTROLLED-SCENARIO ERROR: %s\n' "$*" >&2
    exit 1
}

note() {
    printf 'CONTROLLED-SCENARIO: %s\n' "$*"
}

require_value() {
    local option="$1"
    local value="${2:-}"
    [[ -n "$value" ]] || fail "$option requires a value"
}

is_known_failpoint() {
    case "$1" in
        player_journal.after_temp_write | \
        player_journal.after_temp_fsync | \
        player_journal.after_rename | \
        player_journal.after_directory_fsync | \
        player_journal.after_delete | \
        restoration_journal.after_temp_write | \
        restoration_journal.after_temp_fsync | \
        restoration_journal.after_rename | \
        restoration_journal.after_directory_fsync | \
        restoration_journal.after_delete | \
        block.after_place | \
        block.after_restore | \
        teleport.after_start | \
        teleport.after_return | \
        score.after_commit | \
        reward.after_dispatch | \
        reward_claim.after_commit | \
        reward_outcome.after_commit | \
        export.csv.after_rename | \
        export.json.after_rename | \
        config.backup.after_rename | \
        config.candidate.after_rename | \
        config.after_disk_commit | \
        config.after_runtime_commit)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

while (($# > 0)); do
    case "$1" in
        --paper)
            require_value "$1" "${2:-}"
            paper_jar="$2"
            paper_was_set=true
            shift 2
            ;;
        --skip-build)
            skip_build=true
            shift
            ;;
        --placeholderapi)
            require_value "$1" "${2:-}"
            placeholderapi_jar="$2"
            placeholderapi_was_set=true
            shift 2
            ;;
        --player-assisted)
            player_assisted=true
            shift
            ;;
        --failpoint)
            require_value "$1" "${2:-}"
            failpoint_name="$2"
            shift 2
            ;;
        --occurrence)
            require_value "$1" "${2:-}"
            failpoint_occurrence="$2"
            shift 2
            ;;
        --trigger-command)
            require_value "$1" "${2:-}"
            trigger_command="$2"
            shift 2
            ;;
        --wait-for-player)
            wait_for_player=true
            shift
            ;;
        --failpoint-timeout)
            require_value "$1" "${2:-}"
            failpoint_timeout="$2"
            shift 2
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        -*)
            fail "unknown option: $1"
            ;;
        *)
            if [[ "$paper_was_set" == true ]]; then
                fail "Paper JAR was supplied more than once"
            fi
            paper_jar="$1"
            paper_was_set=true
            shift
            ;;
    esac
done

if [[ -n "$failpoint_name" && "$player_assisted" == true ]]; then
    fail "--player-assisted and --failpoint are separate modes; use --wait-for-player with a failpoint"
fi
if [[ -z "$failpoint_name" && "$wait_for_player" == true ]]; then
    fail "--wait-for-player requires --failpoint"
fi
if [[ -z "$failpoint_name" && -n "$trigger_command" ]]; then
    fail "--trigger-command requires --failpoint"
fi
if [[ -z "$failpoint_name" && "$failpoint_occurrence" != 1 ]]; then
    fail "--occurrence requires --failpoint"
fi
if [[ -n "$failpoint_name" ]] && ! is_known_failpoint "$failpoint_name"; then
    fail "unknown failpoint: $failpoint_name"
fi
if [[ ! "$failpoint_occurrence" =~ ^[0-9]+$ ]] \
        || ((failpoint_occurrence < 1 || failpoint_occurrence > 10000)); then
    fail "--occurrence must be an integer between 1 and 10000"
fi
if [[ ! "$failpoint_timeout" =~ ^[0-9]+$ ]] || ((failpoint_timeout < 1)); then
    fail "--failpoint-timeout must be a positive integer"
fi
if [[ "$trigger_command" == *$'\n'* || "$trigger_command" == *$'\r'* ]]; then
    fail "--trigger-command must be exactly one console command"
fi

if [[ "$paper_jar" != /* ]]; then
    if [[ "$paper_was_set" == true ]]; then
        paper_jar="$invocation_dir/$paper_jar"
    else
        paper_jar="$project_dir/$paper_jar"
    fi
fi
[[ -f "$paper_jar" ]] || fail "Paper JAR not found: $paper_jar"
paper_jar="$(cd "$(dirname "$paper_jar")" && pwd -P)/$(basename "$paper_jar")"

if [[ "$placeholderapi_jar" != /* ]]; then
    if [[ "$placeholderapi_was_set" == true ]]; then
        placeholderapi_jar="$invocation_dir/$placeholderapi_jar"
    else
        placeholderapi_jar="$project_dir/$placeholderapi_jar"
    fi
fi
if [[ -z "$failpoint_name" || "$placeholderapi_was_set" == true ]]; then
    [[ -f "$placeholderapi_jar" ]] \
        || fail "deployable PlaceholderAPI server JAR not found: $placeholderapi_jar"
    placeholderapi_jar="$(
        cd "$(dirname "$placeholderapi_jar")" \
            && printf '%s/%s\n' "$PWD" "$(basename "$placeholderapi_jar")"
    )"
fi

[[ -x "$java_bin" ]] || fail "Java 25 executable not found or not executable: $java_bin"
java_version="$("$java_bin" -version 2>&1 | sed -n '1p')"
[[ "$java_version" == *'"25.'* || "$java_version" == *'"25"'* ]] \
    || fail "Java 25 is required; detected: $java_version"

cleanup() {
    local original_status=$?
    trap - EXIT INT TERM HUP

    if [[ -n "$server_pid" ]] && process_running "$server_pid"; then
        note "stopping leftover disposable server process $server_pid"
        if [[ "$server_fifo_open" == true ]]; then
            printf 'stop\n' >&3 || true
            exec 3>&- || true
            server_fifo_open=false
        fi
        local attempt
        for attempt in $(seq 1 20); do
            process_running "$server_pid" || break
            sleep 1
        done
        if process_running "$server_pid"; then
            kill -TERM "$server_pid" 2>/dev/null || true
        fi
        for attempt in $(seq 1 10); do
            process_running "$server_pid" || break
            sleep 1
        done
        if process_running "$server_pid"; then
            note "forcing leftover disposable server process $server_pid to exit"
            kill -KILL "$server_pid" 2>/dev/null || true
        fi
        wait "$server_pid" 2>/dev/null || true
    elif [[ "$server_fifo_open" == true ]]; then
        exec 3>&- || true
        server_fifo_open=false
    fi

    [[ -z "$server_fifo" ]] || rm -f -- "$server_fifo"
    if [[ "$lock_acquired" == true ]]; then
        [[ "$runner_lock" == "$runtime_root/.runner-lock" ]] \
            && rm -rf -- "$runner_lock"
    fi
    exit "$original_status"
}

trap cleanup EXIT INT TERM HUP

process_running() {
    local pid="$1"
    local state
    kill -0 "$pid" 2>/dev/null || return 1
    state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
    [[ -n "$state" && "$state" != Z* ]]
}

acquire_runner_lock() {
    local owner=""
    require_safe_runtime_root
    mkdir -p "$runtime_root"
    if [[ -L "$runner_lock" ]]; then
        fail "refusing a symlinked controlled-scenario lock"
    fi
    if mkdir "$runner_lock" 2>/dev/null; then
        lock_acquired=true
        printf '%s\n' "$$" >"$runner_lock/pid"
        return 0
    fi

    if [[ -f "$runner_lock/pid" ]]; then
        owner="$(sed -n '1p' "$runner_lock/pid")"
    fi
    if [[ "$owner" =~ ^[0-9]+$ ]] && ! process_running "$owner"; then
        note "removing stale controlled-scenario lock owned by exited pid $owner"
        [[ "$runner_lock" == "$runtime_root/.runner-lock" ]] \
            || fail "refusing to remove unexpected lock path: $runner_lock"
        rm -rf -- "$runner_lock"
        mkdir "$runner_lock"
        lock_acquired=true
        printf '%s\n' "$$" >"$runner_lock/pid"
        return 0
    fi
    fail "another controlled-scenario runner is active (pid=${owner:-unknown})"
}

require_safe_runtime_root() {
    local build_root="$project_dir/build"
    local resolved_root
    [[ "$runtime_root" == "$project_dir/build/controlled-scenarios" ]] \
        || fail "controlled-scenario runtime root is not the expected project build path"
    [[ ! -L "$build_root" ]] \
        || fail "refusing to use a symlinked project build directory"
    [[ ! -L "$runtime_root" ]] \
        || fail "refusing to use a symlinked controlled-scenario runtime root"
    mkdir -p "$runtime_root"
    resolved_root="$(cd "$runtime_root" && pwd -P)"
    [[ "$resolved_root" == "$runtime_root" ]] \
        || fail "controlled-scenario runtime root resolves outside the expected path"
}

strip_ansi() {
    local raw="$1"
    local clean="$2"
    if command -v perl >/dev/null 2>&1; then
        perl -pe 's/\e\[[0-9;?]*[ -\/]*[@-~]//g; s/\r//g' "$raw" >"$clean"
    else
        sed $'s/\033\\[[0-9;?]*[ -\\/]*[@-~]//g; s/\r//g' "$raw" >"$clean"
    fi
}

refresh_log() {
    [[ -f "$server_raw_log" ]] || return 0
    strip_ansi "$server_raw_log" "$server_clean_log"
}

show_log_tail() {
    refresh_log
    if [[ -f "$server_clean_log" ]]; then
        printf '%s\n' '--- disposable server log tail ---' >&2
        tail -n 80 "$server_clean_log" >&2
        printf '%s\n' '--- end server log tail ---' >&2
    fi
}

wait_for_marker() {
    local marker="$1"
    local timeout_seconds="${2:-90}"
    local elapsed
    for elapsed in $(seq 0 "$timeout_seconds"); do
        refresh_log
        if [[ -f "$server_clean_log" ]] && grep -Fq -- "$marker" "$server_clean_log"; then
            note "observed marker: $marker"
            return 0
        fi
        if [[ -n "$server_pid" ]] && ! process_running "$server_pid"; then
            show_log_tail
            fail "server exited before marker: $marker"
        fi
        ((elapsed == timeout_seconds)) || sleep 1
    done
    show_log_tail
    fail "timed out after ${timeout_seconds}s waiting for marker: $marker"
}

assert_marker() {
    local marker="$1"
    refresh_log
    if ! grep -Fq -- "$marker" "$server_clean_log"; then
        show_log_tail
        fail "required marker is missing: $marker"
    fi
}

assert_normal_log() {
    local unexpected_warnings
    refresh_log
    if grep -Fq 'WTP-SCENARIO FAIL ' "$server_clean_log"; then
        show_log_tail
        fail "scenario harness reported a failure"
    fi
    if grep -Eq ' (ERROR|SEVERE)\]:' "$server_clean_log" \
            || grep -Eq \
                '(^|[[:space:]])[a-zA-Z0-9_.]+(Exception|Error)(:|$)|Caused by:|Error occurred while (enabling|disabling)|UnsupportedClassVersionError|NoClassDefFoundError|NoSuchMethodError|ClassNotFoundException|Could not load plugin|Failed to load plugin' \
                "$server_clean_log"; then
        show_log_tail
        fail "an ERROR, exception, or plugin linkage failure was found in the server log"
    fi
    unexpected_warnings="$(
        grep -E ' WARN\]:' "$server_clean_log" \
            | grep -Ev \
                'SERVER IS RUNNING IN OFFLINE/INSECURE MODE|server will make no attempt to authenticate usernames|While this makes the game possible to play without internet access|To change this, set "online-mode" to "true"' \
            || true
    )"
    if [[ -n "$unexpected_warnings" ]]; then
        printf '%s\n' "$unexpected_warnings" >&2
        show_log_tail
        fail "unexpected WARN lines were found; only the explicit offline-mode warning is allowed"
    fi
    assert_marker 'WalkThePlank disabled; arena blocks and player state were restored'
}

assert_failpoint_log() {
    local armed_marker="WTP-SCENARIO FAILPOINT ARMED point=$failpoint_name action=HALT occurrence=$failpoint_occurrence"
    local reached_marker="WTP-SCENARIO FAILPOINT REACHED point=$failpoint_name action=HALT occurrence=$failpoint_occurrence"
    local unexpected_errors
    local unexpected_warnings
    local armed_count
    local reached_count

    refresh_log
    assert_runtime_identity
    armed_count="$(grep -Fc -- "$armed_marker" "$server_clean_log" || true)"
    reached_count="$(grep -Fc -- "$reached_marker" "$server_clean_log" || true)"
    [[ "$armed_count" == 1 ]] \
        || fail "expected exactly one failpoint ARMED marker; found $armed_count"
    [[ "$reached_count" == 1 ]] \
        || fail "expected exactly one failpoint REACHED marker; found $reached_count"

    unexpected_errors="$(
        grep -E ' (ERROR|SEVERE)\]:' "$server_clean_log" \
            | grep -Fv -- "$armed_marker" \
            | grep -Fv -- "$reached_marker" \
            || true
    )"
    if [[ -n "$unexpected_errors" ]]; then
        printf '%s\n' "$unexpected_errors" >&2
        show_log_tail
        fail "unexpected ERROR/SEVERE lines were found in the failpoint phase"
    fi
    if grep -Eq \
        '(^|[[:space:]])[a-zA-Z0-9_.]+(Exception|Error)(:|$)|Caused by:|WTP-SCENARIO FAIL ' \
        "$server_clean_log"; then
        show_log_tail
        fail "an exception or harness failure was found in the failpoint phase"
    fi
    unexpected_warnings="$(
        grep -E ' WARN\]:' "$server_clean_log" \
            | grep -Ev \
                'SERVER IS RUNNING IN OFFLINE/INSECURE MODE|server will make no attempt to authenticate usernames|While this makes the game possible to play without internet access|To change this, set "online-mode" to "true"|Nag author\(s\): .* usage of System.out/err.print' \
            || true
    )"
    if [[ -n "$unexpected_warnings" ]]; then
        printf '%s\n' "$unexpected_warnings" >&2
        show_log_tail
        fail "unexpected WARN lines were found in the failpoint phase"
    fi
}

assert_runtime_identity() {
    local java_line
    local paper_build
    local api_build

    refresh_log
    java_line="$(grep -E '\[bootstrap\] Running Java 25([ .(]|$)' "$server_clean_log" \
        | sed -n '1p')"
    paper_build="$(sed -nE \
        's/.*\[bootstrap\] Loading Paper 26\.2-([0-9]+)-.*/\1/p' \
        "$server_clean_log" \
        | sed -n '1p')"
    api_build="$(sed -nE \
        's/.*Implementing API version 26\.2\.build\.([0-9]+)-beta.*/\1/p' \
        "$server_clean_log" \
        | sed -n '1p')"

    [[ -n "$java_line" ]] || fail "server log does not prove a Java 25 runtime"
    [[ "$paper_build" =~ ^[0-9]+$ ]] \
        || fail "server log does not identify Paper 26.2 and its build"
    [[ "$api_build" =~ ^[0-9]+$ ]] \
        || fail "server log does not identify the Paper 26.2 beta API build"
    ((paper_build >= 60)) \
        || fail "Paper runtime build $paper_build is below required build 60"
    ((api_build >= 60)) \
        || fail "Paper API runtime build $api_build is below required build 60"
    note "verified runtime identity: Java 25, Paper 26.2 build $paper_build (API build $api_build)"
}

record_sqlite_evidence() {
    local server_dir="$1"
    local phase="$2"
    local database_required="$3"
    local database="$server_dir/plugins/InfinityParkour/database.db"
    local report="$server_dir/scenario-logs/$phase.sqlite.txt"
    local query_output
    local journal_mode
    local synchronous
    local foreign_keys
    local user_version
    local quick_check
    local line_count

    command -v sqlite3 >/dev/null 2>&1 \
        || fail "sqlite3 is required to record read-only database evidence"
    if [[ ! -f "$database" ]]; then
        if [[ "$database_required" == true ]]; then
            fail "SQLite database is missing after $phase: $database"
        fi
        printf '%s\n' \
            'database=absent-before-database-initialization' \
            'journal_mode=not-applicable' \
            'synchronous=not-applicable' \
            'foreign_keys=not-applicable' \
            'user_version=not-applicable' \
            'quick_check=not-applicable' \
            >"$report"
        note "SQLite evidence for $phase: database not created before this failpoint"
        return 0
    fi

    if ! query_output="$(
        sqlite3 \
            -readonly \
            -batch \
            -noheader \
            "$database" \
            'PRAGMA journal_mode; PRAGMA synchronous; PRAGMA foreign_keys; PRAGMA user_version; PRAGMA quick_check;'
    )"; then
        fail "read-only SQLite PRAGMA check failed after $phase"
    fi
    journal_mode="$(printf '%s\n' "$query_output" | sed -n '1p')"
    synchronous="$(printf '%s\n' "$query_output" | sed -n '2p')"
    foreign_keys="$(printf '%s\n' "$query_output" | sed -n '3p')"
    user_version="$(printf '%s\n' "$query_output" | sed -n '4p')"
    quick_check="$(printf '%s\n' "$query_output" | sed -n '5,$p')"
    line_count="$(printf '%s\n' "$query_output" | awk 'END { print NR }')"
    [[ -n "$journal_mode" ]] || fail "SQLite journal_mode was blank after $phase"
    [[ "$synchronous" =~ ^[0-9]+$ ]] \
        || fail "SQLite synchronous was not numeric after $phase: $synchronous"
    [[ "$foreign_keys" =~ ^[01]$ ]] \
        || fail "SQLite foreign_keys was not boolean after $phase: $foreign_keys"
    [[ "$user_version" == 2 ]] \
        || fail "SQLite user_version was not schema v2 after $phase: $user_version"
    [[ "$line_count" == 5 && "$quick_check" == ok ]] \
        || fail "SQLite quick_check failed after $phase: ${quick_check:-no result}"

    printf 'database=%s\njournal_mode=%s\nsynchronous=%s\nforeign_keys=%s\nuser_version=%s\nquick_check=%s\n' \
        'plugins/InfinityParkour/database.db' \
        "$journal_mode" \
        "$synchronous" \
        "$foreign_keys" \
        "$user_version" \
        "$quick_check" \
        >"$report"
    note "SQLite evidence for $phase: journal_mode=$journal_mode synchronous=$synchronous foreign_keys=$foreign_keys user_version=$user_version quick_check=ok"
}

send_console() {
    local command="$1"
    [[ "$server_fifo_open" == true ]] || fail "server console FIFO is closed"
    note "console -> $command"
    printf '%s\n' "$command" >&3
}

copy_profile() {
    local server_dir="$1"
    mkdir -p "$server_dir/plugins" "$server_dir/scenario-logs"
    cp "$profile_dir/server.properties" "$server_dir/server.properties"
    cp "$profile_dir/eula.txt" "$server_dir/eula.txt"
}

install_plugins() {
    local server_dir="$1"
    local papi_expectation="$2"
    cp "$scenario_target_jar" "$server_dir/plugins/"
    cp "$scenario_harness_jar" "$server_dir/plugins/"
    find "$server_dir/plugins" -maxdepth 1 -type f -name '*placeholderapi*.jar' -delete
    find "$server_dir/plugins" -maxdepth 1 -type f -name '*PlaceholderAPI*.jar' -delete
    if [[ "$papi_expectation" == present ]]; then
        cp "$placeholderapi_jar" "$server_dir/plugins/"
    fi
}

start_server() {
    local server_dir="$1"
    local phase="$2"
    local papi_expectation="$3"
    shift 3
    local -a extra_properties=("$@")

    [[ -z "$server_pid" ]] || fail "internal error: a server process is already assigned"
    server_raw_log="$server_dir/scenario-logs/$phase.raw.log"
    server_clean_log="$server_dir/scenario-logs/$phase.log"
    server_fifo="$server_dir/scenario-console.fifo"
    server_port=""
    rm -f -- "$server_fifo"
    mkfifo "$server_fifo"

    (
        cd "$server_dir"
        exec "$java_bin" \
            -Xms512m \
            -Xmx1g \
            --enable-native-access=ALL-UNNAMED \
            --sun-misc-unsafe-memory-access=allow \
            -Dwalktheplank.scenario.profile=true \
            "-Dwalktheplank.scenario.root=$server_dir" \
            "-Dwalktheplank.scenario.nonce=$profile_nonce" \
            "-Dwalktheplank.scenario.placeholderapi=$papi_expectation" \
            "${extra_properties[@]}" \
            -jar Paper-26.2.jar \
            --nogui \
            <"$server_fifo" \
            >"$server_raw_log" \
            2>&1
    ) &
    server_pid=$!
    exec 3>"$server_fifo"
    server_fifo_open=true
    note "started $phase with pid=$server_pid PlaceholderAPI=$papi_expectation"
}

discover_server_port() {
    local attempt
    local detected=""
    if ! command -v lsof >/dev/null 2>&1; then
        note "lsof unavailable; server-port=0 remains isolated but the selected port cannot be displayed"
        return 0
    fi
    for attempt in $(seq 1 20); do
        detected="$(
            lsof -nP -a -p "$server_pid" -iTCP -sTCP:LISTEN -Fn 2>/dev/null \
                | sed -n 's/^n.*:\([0-9][0-9]*\)$/\1/p' \
                | tail -n 1
        )"
        [[ -z "$detected" ]] || break
        sleep 1
    done
    if [[ -n "$detected" ]]; then
        server_port="$detected"
        note "Paper selected disposable address 127.0.0.1:$server_port"
    else
        note "Paper is ready, but its ephemeral listening port was not discoverable"
    fi
}

assert_port_released() {
    local released_port="$1"
    local attempt
    [[ -n "$released_port" ]] || return 0
    command -v lsof >/dev/null 2>&1 || return 0
    for attempt in $(seq 1 15); do
        if ! lsof -nP -iTCP:"$released_port" -sTCP:LISTEN >/dev/null 2>&1; then
            note "verified TCP port $released_port was released"
            return 0
        fi
        sleep 1
    done
    fail "TCP port $released_port remained in use after server exit"
}

finish_process() {
    local expected_exit="$1"
    local released_port="$server_port"
    local exit_code

    if [[ "$server_fifo_open" == true ]]; then
        exec 3>&-
        server_fifo_open=false
    fi
    if wait "$server_pid"; then
        exit_code=0
    else
        exit_code=$?
    fi
    server_pid=""
    rm -f -- "$server_fifo"
    server_fifo=""
    refresh_log
    assert_port_released "$released_port"
    if ((exit_code != expected_exit)); then
        show_log_tail
        fail "server exited with $exit_code; expected $expected_exit"
    fi
    note "server exit code $exit_code matched expectation"
}

stop_server_cleanly() {
    local timeout_seconds="${1:-60}"
    local elapsed
    send_console stop
    for elapsed in $(seq 0 "$timeout_seconds"); do
        process_running "$server_pid" || break
        ((elapsed == timeout_seconds)) || sleep 1
    done
    if process_running "$server_pid"; then
        show_log_tail
        fail "server did not stop cleanly within ${timeout_seconds}s"
    fi
    finish_process 0
}

wait_for_failpoint_exit() {
    local timeout_seconds="$1"
    local elapsed
    for elapsed in $(seq 0 "$timeout_seconds"); do
        refresh_log
        process_running "$server_pid" || break
        ((elapsed == timeout_seconds)) || sleep 1
    done
    if process_running "$server_pid"; then
        show_log_tail
        fail "failpoint did not halt the JVM within ${timeout_seconds}s"
    fi
    finish_process 97
}

prepare_artifacts() {
    if [[ "$skip_build" == false ]]; then
        note "building test-only artifacts and proving production scenario isolation"
        (
            cd "$project_dir"
            ./gradlew --no-daemon verifyProductionScenarioIsolation
        )
    else
        note "reusing existing scenario artifacts (--skip-build)"
    fi

    shopt -s nullglob
    local -a targets=("$artifact_dir"/TEST-ONLY-1MB-WalkThePlank-v*-Failpoints.jar)
    local -a harnesses=("$artifact_dir"/TEST-ONLY-1MB-WalkThePlank-ScenarioHarness-v*.jar)
    shopt -u nullglob

    ((${#targets[@]} == 1)) \
        || fail "expected exactly one instrumented target in $artifact_dir; found ${#targets[@]}"
    ((${#harnesses[@]} == 1)) \
        || fail "expected exactly one scenario harness in $artifact_dir; found ${#harnesses[@]}"
    scenario_target_jar="${targets[0]}"
    scenario_harness_jar="${harnesses[0]}"

    local jar_bin="${java_bin%/java}/jar"
    local target_entry
    local harness_entry
    local placeholder_main=""
    local placeholder_metrics=""
    [[ -x "$jar_bin" ]] || fail "Java archive tool not found: $jar_bin"
    target_entry="$(
        "$jar_bin" tf \
            "$scenario_target_jar" \
            com/mrfdev/walktheplank/scenario/instrumentation/ScenarioFailpoints.class
    )"
    harness_entry="$(
        "$jar_bin" tf \
            "$scenario_harness_jar" \
            com/mrfdev/walktheplank/scenario/harness/WalkThePlankScenarioPlugin.class
    )"
    [[ "$target_entry" == \
        com/mrfdev/walktheplank/scenario/instrumentation/ScenarioFailpoints.class ]] \
        || fail "instrumented target is missing the failpoint controller"
    [[ "$harness_entry" == \
        com/mrfdev/walktheplank/scenario/harness/WalkThePlankScenarioPlugin.class ]] \
        || fail "scenario harness is missing its isolated plugin class"
    if [[ -z "$failpoint_name" || "$placeholderapi_was_set" == true ]]; then
        placeholder_main="$(
            "$jar_bin" tf \
                "$placeholderapi_jar" \
                me/clip/placeholderapi/PlaceholderAPIPlugin.class
        )"
        placeholder_metrics="$(
            "$jar_bin" tf \
                "$placeholderapi_jar" \
                me/clip/placeholderapi/metrics/charts/CustomChart.class
        )"
        [[ "$placeholder_main" == me/clip/placeholderapi/PlaceholderAPIPlugin.class ]] \
            || fail "PlaceholderAPI JAR is missing its runtime plugin main class"
        [[ "$placeholder_metrics" == me/clip/placeholderapi/metrics/charts/CustomChart.class ]] \
            || fail "PlaceholderAPI JAR is API/dev-only; a deployable server JAR with relocated metrics is required"
    fi
}

prepare_runtime() {
    mkdir -p "$runtime_root"
    note "using disposable runtime only: $runtime_root (other scenario evidence is preserved)"
}

prepare_server_dir() {
    local server_dir="$1"
    local papi_expectation="$2"
    case "$server_dir" in
        "$runtime_root/two-start" | \
        "$runtime_root/player-assisted" | \
        "$runtime_root/failpoint-"*)
            ;;
        *)
            fail "refusing to replace unexpected scenario directory: $server_dir"
            ;;
    esac
    [[ "$(dirname "$server_dir")" == "$runtime_root" ]] \
        || fail "scenario directory must be an immediate child of $runtime_root"
    [[ ! -L "$server_dir" ]] \
        || fail "refusing to replace a symlinked scenario directory"
    rm -rf -- "$server_dir"
    copy_profile "$server_dir"
    printf 'schema=1\nnonce=%s\n' "$profile_nonce" \
        >"$server_dir/$profile_marker"
    chmod 600 "$server_dir/$profile_marker"
    cp "$paper_jar" "$server_dir/Paper-26.2.jar"
    install_plugins "$server_dir" "$papi_expectation"
}

run_headless_phase() {
    local phase="$1"
    local expectation="$2"
    local prepare_restart="$3"

    start_server "$scenario_server" "$phase" "$expectation"
    wait_for_marker 'WTP-SCENARIO INFO ready commands=/wtpscenario profile=disposable client-dependent-cases=PENDING' 120
    wait_for_marker "WTP-SCENARIO PASS placeholderapi-$expectation" 30
    wait_for_marker 'Done (' 120
    assert_runtime_identity
    discover_server_port
    send_console 'wtpscenario run headless'
    wait_for_marker 'WTP-SCENARIO PASS headless reload=true disable=true service-removed=true' 90
    send_console 'wtpscenario status'
    wait_for_marker 'WTP-SCENARIO INFO status passes=' 30

    if [[ "$prepare_restart" == true ]]; then
        send_console 'wtpscenario restart prepare'
        wait_for_marker 'WTP-SCENARIO INFO restart-marker-prepare status=PREPARED nonce=' 30
    else
        wait_for_marker 'WTP-SCENARIO PASS restart-marker status=CONSUMED same-target-version=true nonce=' 30
    fi

    stop_server_cleanly
    assert_normal_log
    record_sqlite_evidence "$scenario_server" "$phase" true
}

run_automated() {
    scenario_server="$runtime_root/two-start"
    prepare_server_dir "$scenario_server" absent
    note "phase 1/2: PlaceholderAPI absent, reload/terminal disable, durable restart marker"
    run_headless_phase "01-papi-absent" absent true

    install_plugins "$scenario_server" present
    note "phase 2/2: fresh enable on the same profile, PlaceholderAPI present, marker recovery"
    run_headless_phase "02-papi-present" present false

    note "PASS: two-start automated scenario completed"
    note "logs: $scenario_server/scenario-logs"
}

run_player_assisted() {
    [[ -t 0 ]] || fail "--player-assisted requires an interactive terminal"
    scenario_server="$runtime_root/player-assisted"
    prepare_server_dir "$scenario_server" present
    start_server "$scenario_server" "player-assisted" present
    wait_for_marker 'WTP-SCENARIO INFO ready commands=/wtpscenario profile=disposable client-dependent-cases=PENDING' 120
    wait_for_marker 'WTP-SCENARIO PASS placeholderapi-present enabled=true expansion-registered=true' 30
    wait_for_marker 'Done (' 120
    assert_runtime_identity
    discover_server_port
    send_console 'wtpscenario status'
    wait_for_marker 'WTP-SCENARIO INFO status passes=' 30

    cat <<PLAYER

The disposable real-client profile is ready at 127.0.0.1:${server_port:-<inspect the Paper log>}.

This mode does not synthesize Minecraft packets and does not claim that a GUI gesture,
teleport, disconnect, reconnect, exit reason, or simultaneous player action occurred.
Use a real client and the in-game harness commands documented in:
  $project_dir/docs/CONTROLLED-SCENARIOS.md

Run each relevant /wtpscenario ... status command in-game before finishing.
Press Return here only when the real-client session is complete.
PLAYER
    read -r _

    send_console 'wtpscenario status'
    sleep 1
    stop_server_cleanly
    assert_normal_log
    record_sqlite_evidence "$scenario_server" "player-assisted" true
    note "PASS: server-side player-assisted harness remained healthy"
    note "Player gesture/result evidence must be read from: $server_clean_log"
}

run_failpoint() {
    local automatic_startup=false
    local nonce="runner-$(date +%Y%m%d%H%M%S)-$$"
    local -a failpoint_properties=(
        "-Dwalktheplank.scenario.failpoint.name=$failpoint_name"
        "-Dwalktheplank.scenario.failpoint.action=HALT"
        "-Dwalktheplank.scenario.failpoint.occurrence=$failpoint_occurrence"
        "-Dwalktheplank.scenario.failpoint.nonce=$nonce"
    )

    if [[ "$failpoint_name" == config.after_runtime_commit \
            && "$failpoint_occurrence" == 1 \
            && -z "$trigger_command" \
            && "$wait_for_player" == false ]]; then
        trigger_command="walk admin reload"
    fi
    if [[ "$automatic_startup" == false \
            && -z "$trigger_command" \
            && "$wait_for_player" == false ]]; then
        fail "this failpoint is not an automatic first-start boundary; provide --trigger-command or --wait-for-player"
    fi
    if [[ -n "$trigger_command" && "$wait_for_player" == true ]]; then
        fail "choose either --trigger-command or --wait-for-player"
    fi

    scenario_server="$runtime_root/failpoint-$failpoint_name"
    prepare_server_dir "$scenario_server" absent
    note "arming hard-kill failpoint $failpoint_name occurrence=$failpoint_occurrence nonce=$nonce"
    start_server \
        "$scenario_server" \
        "01-failpoint-$failpoint_name" \
        absent \
        "${failpoint_properties[@]}"

    if [[ "$automatic_startup" == false ]]; then
        wait_for_marker 'WTP-SCENARIO INFO ready commands=/wtpscenario profile=disposable client-dependent-cases=PENDING' 120
        wait_for_marker 'Done (' 120
        discover_server_port
        if [[ -n "$trigger_command" ]]; then
            send_console "$trigger_command"
        else
            note "waiting for a real player at 127.0.0.1:${server_port:-<inspect the Paper log>}"
            note "the JVM must halt at $failpoint_name; no player action will be synthesized"
        fi
    fi

    wait_for_failpoint_exit "$failpoint_timeout"
    assert_failpoint_log
    record_sqlite_evidence \
        "$scenario_server" \
        "01-failpoint-$failpoint_name" \
        false
    note "expected hard halt observed; restarting the same disposable profile without controls"

    install_plugins "$scenario_server" absent
    start_server "$scenario_server" "02-recovery-$failpoint_name" absent
    wait_for_marker 'WTP-SCENARIO PASS target-enabled version=' 120
    wait_for_marker 'WTP-SCENARIO PASS startup-api service-registered=true' 30
    wait_for_marker 'WTP-SCENARIO PASS placeholderapi-absent installed=false target-class-visible=false' 30
    wait_for_marker 'Done (' 120
    assert_runtime_identity
    discover_server_port
    send_console 'wtpscenario status'
    wait_for_marker 'WTP-SCENARIO INFO status passes=' 30
    stop_server_cleanly
    assert_normal_log
    record_sqlite_evidence \
        "$scenario_server" \
        "02-recovery-$failpoint_name" \
        true
    note "PASS: exit 97 and clean recovery restart completed for $failpoint_name"
    note "logs: $scenario_server/scenario-logs"
}

acquire_runner_lock
prepare_artifacts
prepare_runtime

if [[ -n "$failpoint_name" ]]; then
    run_failpoint
elif [[ "$player_assisted" == true ]]; then
    run_player_assisted
else
    run_automated
fi
