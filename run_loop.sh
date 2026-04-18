#!/usr/bin/env bash

set -u
set -o pipefail

SCRIPT_NAME="$(basename "$0")"

# Defaults
N=15         # runs per cycle
X=10         # minutes to sleep after each full cycle
Y=5          # minutes to sleep after a failed run
Z=3          # max consecutive failures before exit

VERBOSE=1

START_TS=$(date +%s)

usage() {
  cat <<EOF
Usage: $SCRIPT_NAME [options]

Runs a block of commands N times, then sleeps X minutes, and repeats forever.
If a run fails, the script sleeps Y minutes, increments the consecutive error
counter, and exits after Z consecutive failures.

Options:
  -n, --runs N              Number of runs per cycle (default: $N)
  -x, --sleep-minutes X     Minutes to sleep after each cycle (default: $X)
  -y, --error-sleep Y       Minutes to sleep after a failed run (default: $Y)
  -z, --max-errors Z        Exit after Z consecutive failures (default: $Z)
  -q, --quiet               Reduce log output
  -h, --help                Show this help message and exit
EOF
}

elapsed_time() {
  local now elapsed h m s
  now=$(date +%s)
  elapsed=$((now - START_TS))
  h=$((elapsed / 3600))
  m=$(((elapsed % 3600) / 60))
  s=$((elapsed % 60))
  printf '%02d:%02d:%02d' "$h" "$m" "$s"
}

log() {
  if [[ "$VERBOSE" -eq 1 ]]; then
    printf '[%s] [elapsed=%s] [cycle=%s] %s\n' \
      "$(date '+%Y-%m-%d %H:%M:%S')" \
      "$(elapsed_time)" \
      "${cycle:-0}" \
      "$*"
  fi
}

err() {
  printf '[%s] [elapsed=%s] [cycle=%s] ERROR: %s\n' \
    "$(date '+%Y-%m-%d %H:%M:%S')" \
    "$(elapsed_time)" \
    "${cycle:-0}" \
    "$*" >&2
}

die() {
  err "$*"
  exit 1
}

require_value() {
  local opt="$1"
  local val="${2-}"
  [[ -n "$val" ]] || die "Option '$opt' requires a value. Use --help for usage."
}

is_non_negative_int() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

is_positive_int() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

run_step() {
  local label="$1"
  shift
  log "$label"
  "$@"
}

run_block() {

  run_step "🟢 written" \
    opencode run --model opencode-go/minimax-m2.7 "@prompts/loop.prompt.md" || return $?

  run_step "🚀 pushing to remote" \
    git push origin || return $?

  return 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--runs)
      require_value "$1" "${2-}"
      N="$2"
      shift 2
      ;;
    -x|--sleep-minutes)
      require_value "$1" "${2-}"
      X="$2"
      shift 2
      ;;
    -y|--error-sleep)
      require_value "$1" "${2-}"
      Y="$2"
      shift 2
      ;;
    -z|--max-errors)
      require_value "$1" "${2-}"
      Z="$2"
      shift 2
      ;;
    -q|--quiet)
      VERBOSE=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1. Use --help for usage."
      ;;
  esac
done

is_positive_int "$N" || die "N/--runs must be a positive integer."
is_non_negative_int "$X" || die "X/--sleep-minutes must be a non-negative integer."
is_non_negative_int "$Y" || die "Y/--error-sleep must be a non-negative integer."
is_positive_int "$Z" || die "Z/--max-errors must be a positive integer."

trap 'err "Interrupted. Exiting."; exit 130' INT TERM

consecutive_errors=0
cycle=1

log "Starting runner with: N=$N, X=${X}m, Y=${Y}m, Z=$Z, verbose=$VERBOSE"

while true; do
  log "Starting cycle"

  for (( run=1; run<=N; run++ )); do
    log "Run $run/$N"

    if run_block; then
      consecutive_errors=0
      log "Run $run/$N succeeded. Consecutive error count reset to 0."
    else
      exit_code=$?
      ((consecutive_errors++))
      err "Run $run/$N failed with exit code $exit_code. Consecutive errors: $consecutive_errors/$Z."

      if (( consecutive_errors >= Z )); then
        err "Reached $Z consecutive failures. Exiting."
        exit "$exit_code"
      fi

      if (( Y > 0 )); then
        log "Sleeping for $Y minute(s) after failure."
        sleep "${Y}m"
      else
        log "Failure backoff is 0 minute(s); continuing immediately."
      fi
    fi
  done

  log "Cycle completed. Total cycles since beginning: $cycle. Sleeping for $X minute(s) before next cycle."
  ((cycle++))
  sleep "${X}m"
done
