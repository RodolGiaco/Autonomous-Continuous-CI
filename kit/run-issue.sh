#!/usr/bin/env bash
# Runs one issue end to end: branch, implementation, push, pull request and
# queued merge. Takes the issue number as an explicit argument; it never infers
# which issue to work on.
#
# The run ends in exactly one declared state: merged, queued, or open with a
# reason. It never reports success it did not observe.
set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" \
  || { echo "runner: not inside a git repository" >&2; exit 1; }
cd "$REPO_ROOT" || exit 1

ISSUE_NUMBER="${1:-}"
[ -n "$ISSUE_NUMBER" ] || { echo "usage: bash kit/run-issue.sh <issue-number>" >&2; exit 1; }
case "$ISSUE_NUMBER" in *[!0-9]*) echo "runner: the issue number must be digits" >&2; exit 1 ;; esac

step() { printf '\n=== %s\n' "$1"; }
die()  { printf 'runner: %s\n' "$1" >&2; exit 1; }

# -------------------------------------------------------------- variables --
LOCAL_ENV="$REPO_ROOT/.env.local"
OPENROUTER_ENV="${HOME}/.config/claude-code/openrouter.env"

set -a
# shellcheck disable=SC1091
. .claude/kit.vars || die "cannot read .claude/kit.vars"

if [ -f "$LOCAL_ENV" ]; then
  # shellcheck disable=SC1090
  . "$LOCAL_ENV" || die "cannot read $LOCAL_ENV"
fi

set +a
: "${KIT_MAIN_BRANCH:?not set in kit.vars}"
: "${KIT_ISSUE_BRANCH_PREFIX:?not set in kit.vars}"

# --------------------------------------------------------------- provider --
CLAUDE_CMD=(claude)

if [ "${USE_OPENROUTER:-false}" = "true" ]; then
  [ -f "$OPENROUTER_ENV" ] \
    || die "OpenRouter environment file not found: $OPENROUTER_ENV"

  set -a
  # shellcheck disable=SC1090
  . "$OPENROUTER_ENV" || die "cannot read $OPENROUTER_ENV"
  set +a

  CLAUDE_CMD=(
    claude
    --model "${CLAUDE_MODEL:-openrouter/free}"
  )

  printf 'runner: using OpenRouter model: %s\n' \
    "${CLAUDE_MODEL:-openrouter/free}"
else
  unset ANTHROPIC_BASE_URL
  unset ANTHROPIC_AUTH_TOKEN
  unset ANTHROPIC_API_KEY
  unset OPENROUTER_API_KEY
  unset CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY
  unset CLAUDE_CODE_SKIP_FAST_MODE_ORG_CHECK
  unset CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT
  unset CLAUDE_MODEL

  printf 'runner: using Claude default authentication and model\n'
fi

# ---------------------------------------------------------------- preflight --
# Every command the run depends on is checked before anything is mutated,
# because a run that fails halfway leaves a branch and a state file behind.
step "Preflight"
bash kit/doctor.sh || die "doctor reported failures; fix them before running an issue"

git diff --quiet && git diff --cached --quiet \
  || die "the working tree has uncommitted changes; the run would mix them into the issue"

# ------------------------------------------------------------------- branch --
step "Branch"
# --prune because the forge deletes a branch when its pull request merges, and
# a stale remote-tracking ref would read as a branch that still exists there.
git fetch origin --prune --quiet || die "git fetch failed"
MAIN_REF="origin/${KIT_MAIN_BRANCH}"

TITLE=$(gh issue view "$ISSUE_NUMBER" --json title --jq .title) \
  || die "cannot read issue $ISSUE_NUMBER from the forge"
[ -n "$TITLE" ] || die "issue $ISSUE_NUMBER has an empty title"

# The slug only feeds the branch name, so anything outside the allowed set is
# dropped rather than escaped. The guards enforce the resulting shape.
SLUG=$(printf '%s' "$TITLE" \
  | tr '[:upper:]' '[:lower:]' \
  | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
  | cut -c1-40 | sed -E 's/-+$//')
[ -n "$SLUG" ] || die "the issue title produced an empty slug"

BRANCH="${KIT_ISSUE_BRANCH_PREFIX}${ISSUE_NUMBER}-${SLUG}"

# A merged pull request means the issue is done, whether or not its branch
# survived the merge. Checked before the base: a squash merge always moves the
# protected branch past the base, so the base check would misname the cause.
MERGED_PR=$(gh pr list --head "$BRANCH" --state merged --json number --jq '.[0].number // empty') \
  || die "cannot read the pull requests of $BRANCH from the forge"
[ -z "$MERGED_PR" ] \
  || die "issue #$ISSUE_NUMBER is done: pull request #$MERGED_PR from $BRANCH is merged"

# An existing branch is resumed rather than recreated, so a run that failed
# halfway keeps its commits. The local branch takes precedence over origin.
RESUMED=no
START="$MAIN_REF"
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  RESUMED=yes
  START="$BRANCH"
  # The push is never forced, so the local branch has to hold every commit
  # origin has, or the push is refused after the session has been paid for.
  if git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
    git merge-base --is-ancestor "origin/$BRANCH" "$BRANCH" \
      || die "$BRANCH lacks commits that origin/$BRANCH has; reconcile them before running"
  fi
elif git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
  RESUMED=yes
  START="origin/$BRANCH"
fi

# The runner never rebases or merges, so a branch cut before the protected
# branch moved stays behind it. Its diff would read as a revert of the
# newer commits, and the code reviewer blocks that.
if [ "$RESUMED" = yes ]; then
  BASE=$(git merge-base "$MAIN_REF" "$START") \
    || die "$START shares no history with $MAIN_REF"
  MAIN_TIP=$(git rev-parse --verify --quiet "${MAIN_REF}^{commit}") \
    || die "cannot resolve $MAIN_REF"
  [ "$BASE" = "$MAIN_TIP" ] \
    || die "$BRANCH has a stale base: it forks from $MAIN_REF at ${BASE:0:12}, which has since moved to ${MAIN_TIP:0:12}"
fi

START_COMMIT=$(git rev-parse --verify --quiet "${START}^{commit}") \
  || die "cannot resolve $START"

if [ "$RESUMED" = no ]; then
  git switch -c "$BRANCH" "$MAIN_REF" --quiet \
    || die "cannot create $BRANCH from $MAIN_REF"
elif [ "$START" = "$BRANCH" ]; then
  git switch "$BRANCH" --quiet || die "cannot switch to $BRANCH"
else
  git switch -c "$BRANCH" --track "$START" --quiet \
    || die "cannot create $BRANCH from $START"
fi

# A mutation is not applied until it is read back.
ACTUAL=$(git symbolic-ref --quiet --short HEAD)
[ "$ACTUAL" = "$BRANCH" ] || die "expected to be on $BRANCH, found $ACTUAL"
[ "$(git rev-parse HEAD)" = "$START_COMMIT" ] || die "$BRANCH is not at $START after the switch"
PRIOR_COMMITS=$(git rev-list --count "${MAIN_REF}..HEAD") \
  || die "cannot count the commits on $BRANCH"
if [ "$RESUMED" = yes ]; then
  echo "resuming $BRANCH from $START with $PRIOR_COMMITS earlier commits"
else
  echo "on $BRANCH"
fi

# -------------------------------------------------------------------- state --
# Read by the SessionStart hook. Written with jq because the title comes from
# the forge and is not trusted input.
mkdir -p .claude/kit-state
jq -nc --arg n "$ISSUE_NUMBER" --arg t "$TITLE" --arg b "$BRANCH" \
  '{number: ($n|tonumber), title: $t, branch: $b}' \
  > .claude/kit-state/current-issue.json \
  || die "cannot write the session state"
jq -e --arg n "$ISSUE_NUMBER" --arg b "$BRANCH" \
  '.number == ($n|tonumber) and .branch == $b' \
  < .claude/kit-state/current-issue.json >/dev/null \
  || die "the session state does not record issue $ISSUE_NUMBER on $BRANCH"

# --------------------------------------------------------------- the session --
step "Session"
RESULT=$(mktemp)
trap 'rm -f "$RESULT"' EXIT

# A headless session ends with the model's turn. Measured on an issue run: the
# session started the tests in the background, ended its turn to wait for them,
# and exited with the work uncommitted. The prompt says so explicitly.
PROMPT="Work issue #${ISSUE_NUMBER} to completion on the current branch.
Read the issue with gh issue view ${ISSUE_NUMBER}. Implement every acceptance
criterion it states. Commit your work on this branch with a message that names
the issue. Delegate to the code-reviewer and acceptance-auditor subagents before
your final commit and address any blocking finding they return. Do not push and
do not open a pull request: the runner does that.
Run every command in the foreground and wait for its result, tests included.
This session ends as soon as you end your turn, so a command left running in
the background never reports back and the work after it never happens."

# A resumed branch carries work from an earlier run. Without this the model
# reads the issue as unstarted and redoes or discards it.
if [ "$PRIOR_COMMITS" -gt 0 ]; then
  PROMPT="${PROMPT}
This branch already holds ${PRIOR_COMMITS} commits from an earlier run on this
issue. Continue that work; do not start over. Read it first with
git log ${MAIN_REF}..HEAD and git diff ${MAIN_REF}...HEAD, keep what is correct,
and build on it."
fi

# No --bare: the run needs the project hooks, agents and permission rules.
# dontAsk because nobody is here to answer a prompt.
"${CLAUDE_CMD[@]}" -p "$PROMPT" \
  --permission-mode dontAsk \
  --output-format json > "$RESULT"

# The result is kept: it is the only record of what a session was refused and
# what it cost, and .claude/logs is ignored by git.
mkdir -p .claude/logs
cp "$RESULT" ".claude/logs/session-${ISSUE_NUMBER}-$(date +%Y%m%dT%H%M%S).json"

# is_error is false when a permission denial stops a command, so the exit code
# and that field both lie. The denial list is the evidence.
DENIALS=$(jq -r '.permission_denials | length' < "$RESULT" 2>/dev/null || echo "unknown")
COST=$(jq -r '.total_cost_usd // 0' < "$RESULT" 2>/dev/null || echo 0)
printf 'cost: %s USD   denials: %s\n' "$COST" "$DENIALS"

if [ "$DENIALS" != "0" ]; then
  jq -r '.permission_denials[] | "  denied: \(.tool_name) \(.tool_input | .command // .file_path // .skill // (tostring | .[0:160]))"' < "$RESULT"
  die "the session was blocked; the branch is left in place for inspection"
fi

# A session that ends with files it did not commit leaves work the push would
# drop, and the next run would stop on a dirty tree without saying why.
[ -z "$(git status --porcelain)" ] \
  || die "the session left uncommitted changes; the branch is left in place for inspection"

COMMITS=$(git rev-list --count "${MAIN_REF}..HEAD")
[ "$COMMITS" -gt 0 ] || die "the session produced no commit; nothing to push"
echo "commits on the branch: $COMMITS"
# On a resumed branch the total includes the earlier run, which may already be
# complete; the session's own share is reported so the log shows which it was.
if [ "$RESUMED" = yes ]; then
  echo "commits from this session: $(git rev-list --count "${START_COMMIT}..HEAD")"
fi

# ------------------------------------------------------------- local checks --
# The same commands CI runs. A red result here stops the run before the push
# pays for two review sessions on code that cannot merge.
step "Local checks"
for var in KIT_FORMAT_CHECK_CMD KIT_SPEC_CHECK_CMD KIT_TEST_CMD; do
  cmd="${!var:-}"
  [ -n "$cmd" ] || continue
  echo "running: $cmd"
  eval "$cmd" || die "$var failed locally; the branch is left in place, fix it and run this issue again"
done

# --------------------------------------------------------------------- push --
# The runner pushes, not the model: this step has to be auditable in the log.
step "Push and pull request"
git push -u origin "$BRANCH" --quiet || die "push rejected"

# gh pr create takes no --json flag, so the number is not read from its output.
# It is asked for afterwards, which also covers the case of a pull request that
# was already open for this branch.
gh pr create --fill --base "$KIT_MAIN_BRANCH" --head "$BRANCH" >/dev/null 2>&1 || true
PR=$(gh pr list --head "$BRANCH" --state open --json number --jq '.[0].number // empty')
[ -n "$PR" ] || die "cannot determine the pull request number"
echo "pull request #$PR"

# ------------------------------------------------------------------- merge --
# Measured on this repository: the auto-merge request does not survive the
# cycle of a failing check followed by a fix. It is requested and then read
# back, and requested again when it did not stick.
step "Queue the merge"
QUEUED=no
for attempt in 1 2 3; do
  gh pr merge "$PR" --auto --squash >/dev/null 2>&1 || true
  sleep 5
  if [ "$(gh pr view "$PR" --json autoMergeRequest --jq '.autoMergeRequest != null')" = "true" ]; then
    QUEUED=yes
    break
  fi
  if [ "$(gh pr view "$PR" --json state --jq .state)" = "MERGED" ]; then
    QUEUED=merged
    break
  fi
  echo "auto-merge did not stick on attempt $attempt"
done

# -------------------------------------------------------------------- state --
step "Result"
STATE=$(gh pr view "$PR" --json state --jq .state)
case "$STATE:$QUEUED" in
  MERGED:*)
    echo "MERGED   issue #$ISSUE_NUMBER, pull request #$PR"
    rm -f .claude/kit-state/current-issue.json
    exit 0 ;;
  OPEN:yes)
    echo "QUEUED   issue #$ISSUE_NUMBER, pull request #$PR waits for the required checks"
    exit 0 ;;
  *)
    echo "OPEN     issue #$ISSUE_NUMBER, pull request #$PR is not queued"
    gh pr view "$PR" --json mergeStateStatus,statusCheckRollup \
      --jq '{mergeStateStatus, checks: [.statusCheckRollup[] | {name, conclusion}]}'
    exit 1 ;;
esac
