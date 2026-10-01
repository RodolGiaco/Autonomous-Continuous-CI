# Automation kit

A reusable setup that lets Claude Code work GitHub issues on its own inside a
branch, while the merge into the protected branch stays out of its reach.

Copy `kit/` into a repository, run `bash kit/install.sh`, edit
`.claude/kit.vars`, and run `bash kit/github/apply-protection.sh` to install the
GitHub protection layer. Nothing else in the kit contains a project-specific
value.

## The three layers, and what each one actually guarantees

Read this before trusting any of them. Two of the three are evadable, and
saying so plainly is what keeps the third one from being weakened.

### Layer 1 — Permission rules (`.claude/settings.json`)

Declarative. They only see the text of the command. Rules are evaluated deny,
then ask, then allow, and the first match wins regardless of specificity, so a
deny rule cannot carry allowlist exceptions. This is where absolutes live.

Measured in this repository: a deny rule beats an `--allowedTools` flag passed
on the command line, and it beats the built-in read-only command set.

### Layer 2 — PreToolUse hooks (`.claude/hooks/`)

Code. They resolve repository state, so they can express what a text rule
cannot: which branch you are standing on, whether a flag is present anywhere in
the command, whether a merge was queued rather than performed.

**This layer is not a security boundary, and the kit does not treat it as one.**
It is evadable in at least four measured ways: a shell script that wraps a
blocked command runs it anyway, because permission rules do not reach
subprocesses; `--settings` with `disableAllHooks` turns every project hook off
for a run; `--bare` starts a session without loading hooks, agents, skills, MCP
servers or CLAUDE.md; and the model, when refused one tool, may reach the same
effect with another.

What this layer does earn its place for is **feedback inside the turn**. When a
guard refuses, the reason is returned to the model, which corrects itself in the
same turn instead of failing later in CI. That was measured here: a refusal from
`guard-merge.sh` came back quoted verbatim in the session result, and the model
stated it would not look for an alternative route.

Every refusal is appended to `.claude/logs/guard-denials.jsonl`. That record is
the evidence for deciding, later, whether this layer still pays for itself.

### Layer 3 — The GitHub ruleset (`kit/github/ruleset-main.json`)

The only layer the model cannot switch off. It requires a pull request, forbids
deletion and non-fast-forward, and requires status checks. `bypass_actors` is
empty on purpose: nobody bypasses it, including the repository owner.

Install or update this layer with:

```bash
bash kit/github/apply-protection.sh
```

The script applies `kit/github/ruleset-main.json`, updates the existing
`protect-main` ruleset instead of duplicating it, enables auto-merge, and reads
the resulting configuration back from GitHub to verify it.

On a repository with no merged pull requests yet, no check run exists from
which to determine the publishing GitHub App. In that case the script installs
the ruleset without required status checks. Run it again after the first pull
request is merged so the required checks can be added and bound to the App that
actually publishes them.

Measured: a direct push to the protected branch is refused by the server with
`push declined due to repository rule violations`.

**Each required status check is bound to the app that publishes it.** Without
that binding, anyone with write access to the repository can set the state of
any status check, which would let the agent publish its own green verdict. The
`integration_id` in the ruleset is what closes that.

## Why each deny entry exists

JSON takes no comments, so the justification lives here.

- `Bash(claude *)` — without it the agent can start another instance with
  `disableAllHooks` and turn the whole hook layer off, or with `--bare` and
  start with no project configuration at all. An agent that can invoke itself
  has no guards.
- `Bash(gh api *)` — raw API access is access to delete the branch ruleset and
  to publish the approval status of its own pull request. Everything the cycle
  needs from the API goes through named kit scripts instead. The difference is
  not power, it is that the artifact is reviewed and stable.
- `Bash(gh auth *)` — prevents switching identity or token mid-run, which would
  invalidate any reasoning about who did what.
- `Bash(gh repo delete *)` — no valid form inside the cycle.
- `Read(.env)` and `Edit(.env)` — a Read deny already blocks Edit and Write on
  the same path but not NotebookEdit, so both are listed.

The allow rules let a headless session write only under `src/`, `docs/` and
`openspec/`: the code, and the documentation and the spec record that have to
change with it. Everything else,
the kit and the workflows included, is outside its reach, and `Edit(/kit/**)`,
`Edit(/.github/**)` and `Edit(/CLAUDE.md)` ask, which a headless run turns
into a refusal. `doctor.sh` fails when an allowed path does not exist, so a
repository without `docs/` or `openspec/` creates it or removes those rules.

Deliberately absent: rules against `git push --force` and against
`gh pr merge` without `--auto`. Written as deny rules they would be theatre:
the first is evaded by moving the flag, and the second cannot be expressed at
all because a deny rule carries no exceptions. Both live in the hooks.

## Verify before you trust

```bash
bash kit/github/apply-protection.sh  # apply/update and verify the GitHub barrier
bash kit/doctor.sh                   # installation, consistency, and the server ruleset
bash kit/test-guards.sh              # 63 hook cases, offline, no session spent
```

`apply-protection.sh` verifies the server-side protection immediately after
applying it. `doctor.sh` fails when the installed hooks drift from `kit/hooks`,
when a review subagent is missing, or when no active ruleset protects the
default branch. That last check is the one that matters most: without it,
someone installs the kit in an unprotected repository and believes they are
covered.

## Run one issue

```bash
bash kit/run-issue.sh <issue-number>
```

The run ends in a declared outcome: merged, queued, or a stop with its reason
and a non-zero exit. It reads `permission_denials` rather than the exit code,
because a run stopped by a permission refusal still reports success.

### Local settings

The runner sources `.env.local` at the repository root when the file exists.
It holds per-machine values, so keep it out of version control.

`USE_OPENROUTER=true` there routes the headless session through OpenRouter
instead of the default Claude authentication, so the cycle keeps running when
the subscription quota is spent. The runner then sources
`~/.config/claude-code/openrouter.env`, which holds the variables Claude Code
needs to reach OpenRouter, and passes `--model` with `CLAUDE_MODEL`, or with
`openrouter/free` when that is unset. With any other value, the runner unsets
the gateway variables, such as `ANTHROPIC_BASE_URL` and
`ANTHROPIC_AUTH_TOKEN`, and uses the default authentication and model.

## Session context

`session-context.sh` runs on every SessionStart: at startup, on resume and
after each compaction. It restates the facts a session needs and can lose to a
compaction: the protected branch, the two work-branch shapes, what the guards
refuse, the current branch and its uncommitted entries.

It names the issue in progress, read from `.claude/kit-state/current-issue.json`,
only while that issue's branch is checked out. The runner keeps the file after
a run that ends with the merge queued, and on another branch that issue may
well be finished.

The hook never blocks, and its text states facts rather than instructions:
text framed as out-of-band commands can trip Claude's prompt-injection
defences and be shown instead of used.

## Known limits

- `run-issue.sh` resumes the branch a failed run left behind, but only while
  the protected branch has not moved past its base. The runner never rebases
  or merges, so a stale branch stops the run and has to be rebuilt by hand. An
  issue whose pull request is merged also stops the run.
- A subagent verdict does not survive the subagent dying mid-pass. `maxTurns`
  caps the damage; it does not remove it.
- The acceptance auditor reports every criterion as unverifiable while
  `KIT_TEST_CMD` is empty.

## Further reading

The source repository documents the hooks, the required checks and every
failure message of the kit in depth:
https://github.com/RodolGiaco/gated-agent-workflow