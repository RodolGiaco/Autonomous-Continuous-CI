# Troubleshooting

Every message below is printed by a script of the kit, word for word; values in angle brackets vary. Find the message, read the cause, apply the fix.

## Contents

- [install.sh](#installsh)
- [doctor.sh](#doctorsh)
- [run-issue.sh](#run-issuesh)
- [apply-protection.sh](#apply-protectionsh)
- [The review gate](#the-review-gate)
- [Guard refusals](#guard-refusals)

## install.sh

| Message | Cause | Fix |
|---|---|---|
| `install: jq is required by the hooks. Install it and run this again.` | Every hook reads its input and writes its decision with jq. | Install jq and run the installer again. |
| `install: warning: gh is not installed. The cycle cannot reach the forge without it.` | The runner and the protection script talk to GitHub through gh. | Install gh and run `gh auth login`. |
| `install: not inside a git repository` | The installer works on the repository it runs from. | Run it from inside the target repository. |

## doctor.sh

Each check prints `PASS`, `FAIL` with a hint on the next line, or `SKIP`. The script exits non-zero when any check fails.

| Check | Cause of a `FAIL` | Fix |
|---|---|---|
| `jq is installed` | Every hook needs jq. | Install jq. |
| `gh is installed`, `gh is authenticated` | The cycle reaches GitHub through gh. | Install gh, then `gh auth login`. |
| `installed hooks match kit/hooks`, `installed settings.json matches kit/settings.json` | An installed copy differs from its source, usually because it was edited in place. | Edit the file under `kit/`, then run `bash kit/install.sh`. |
| `review subagents are present` | `.claude/agents/code-reviewer.md` or `acceptance-auditor.md` is missing. | Run `bash kit/install.sh` and commit `.claude/agents`. |
| `installed agents match kit/agents`, `installed workflows match kit/workflows` | The committed copies drifted from `kit/`. | Run `bash kit/install.sh`, then commit `.claude/agents` and `.github/workflows`. |
| `the review token secret is set` | `CLAUDE_CODE_OAUTH_TOKEN` is missing, and the review workflow fails without it. A `SKIP` means the secrets cannot be listed with the current credentials, which needs admin rights. | `claude setup-token`, then `gh secret set CLAUDE_CODE_OAUTH_TOKEN`. |
| `kit.vars defines every required key` | `KIT_MAIN_BRANCH`, `KIT_ISSUE_BRANCH_PREFIX` or `KIT_MAINTENANCE_BRANCH_PREFIX` is missing or empty, and every hook aborts without them. | Set them in `.claude/kit.vars`. |
| `kit.vars values with spaces are quoted` | The file is sourced, so an unquoted value with a space runs its remainder as a command and breaks every guard at once. | Quote the value on the reported line. |
| `permission rules point at paths that exist` | An allow rule anchored at a path that does not exist grants nothing. | Fix the path in `kit/settings.json`, then run `bash kit/install.sh`. |
| `hook scripts are executable` | A hook lost its executable bit. | Run `bash kit/install.sh`. |
| `.claude/kit-state is ignored by git` | Session state carries data from GitHub and must never be committed. | Restore the kit block in `.gitignore` from `kit/gitignore.fragment`. |
| `an active ruleset protects the default branch` | Without the ruleset only the local layers remain, and they are evadable. | Run `bash kit/github/apply-protection.sh`. |

## run-issue.sh

A stop prints `runner: <message>` and exits 1. Whatever the run created before the stop stays in place, and running the same issue again resumes its branch.

| Message | Cause | Fix |
|---|---|---|
| `doctor reported failures; fix them before running an issue` | The preflight runs `doctor.sh`. | Fix the failing checks, see [doctor.sh](#doctorsh). |
| `the working tree has uncommitted changes; the run would mix them into the issue` | Local changes would end up in the issue's commits. | Commit or stash them. |
| `cannot read issue <n> from the forge` | gh cannot read the issue: a wrong number, another repository, or no authentication. | Check it with `gh issue view <n>`. |
| `the issue title produced an empty slug` | The title has no letter or digit to build the branch name from. | Retitle the issue. |
| `issue #<n> is done: pull request #<p> from <branch> is merged` | The issue's pull request is already merged. | Nothing to run. Open a new issue for further work. |
| `<branch> lacks commits that origin/<branch> has; reconcile them before running` | The remote branch holds commits the local one does not, and the runner never force pushes. | Bring the local branch up to date with `origin/<branch>`, for example with `git pull --ff-only` on it. |
| `<branch> has a stale base: it forks from origin/main at <sha>, which has since moved to <sha>` | The protected branch moved after the branch was cut. The runner never rebases or merges, and the diff would read as a revert of the newer commits. | Close its pull request if one is open, delete the branch locally and on the remote, and run again: the new branch starts from the current protected branch. |
| `OpenRouter environment file not found: <path>` | `USE_OPENROUTER=true` in `.env.local`, but `~/.config/claude-code/openrouter.env` is missing. | Create that file, or set `USE_OPENROUTER=false`. |
| `the session was blocked; the branch is left in place for inspection` | A permission rule or a guard denied a command during the session. The `denied:` lines above the message list what was refused. | Read the reasons in `.claude/logs/guard-denials.jsonl`, fix the cause, and run again. |
| `the session left uncommitted changes; the branch is left in place for inspection` | The session ended with modified or untracked files it never committed: usually a new file that was never added, or a turn that ended while a command ran in the background, since a headless session ends with the turn. Build output that `.gitignore` does not cover trips it too. | Run `git status` on the issue branch. Commit what belongs to the issue, discard the rest with `git restore` or `git clean`, and add build output to `.gitignore`. Then run again. |
| `the session produced no commit; nothing to push` | The session ended with a clean tree and no commit. When the line above it reads `cost: 0 USD`, the session never reached the model, usually because the sign-in expired. | Sign in again if the cost is 0. Otherwise read the session output and the issue, then run again. |
| `<KIT_FORMAT_CHECK_CMD or KIT_TEST_CMD> failed locally; the branch is left in place, fix it and run this issue again` | The format check or the tests failed on the committed work, before the push. CI would fail the same way, after paying for both reviews. | Run the command from `.claude/kit.vars` yourself to read the failure. Then run the issue again: the session resumes the branch. |
| `push rejected` | The remote refused the push. | Read the git error printed above the message. |
| `cannot determine the pull request number` | `gh pr create` failed, and no open pull request exists for the branch. | Check with `gh pr list --head <branch>`, then run again. |

The run can also end without a stop:

| Last line | Meaning | Exit code |
|---|---|---|
| `MERGED   issue #<n>, pull request #<p>` | GitHub merged the pull request during the run. | 0 |
| `QUEUED   issue #<n>, pull request #<p> waits for the required checks` | Auto-merge is on: GitHub merges once both checks pass. | 0 |
| `OPEN     issue #<n>, pull request #<p> is not queued` | Auto-merge could not be requested. The merge state and the checks are printed below the line. | 1 |

For an `OPEN` result, enable auto-merge on the repository (`bash kit/github/apply-protection.sh` does it) or fix the failing check.

## apply-protection.sh

| Message | Cause | Fix |
|---|---|---|
| `protection: warning: rulesets need a paid plan on a private repository.` followed by a 403 | Rulesets on a private repository need a paid plan. | Make the repository public, or use a paid plan. |
| `protection: no check run found yet, so required status checks are left out for now` | No pull request has merged yet, or its head commit has check runs from more than one app, so there is no app id to bind the checks to. | Merge a pull request whose checks ran, then run the script again. |
| `protection: warning: auto-merge could not be enabled; enable it in the repository settings` | The credentials cannot change the repository settings. | Enable auto-merge in the repository settings. |
| `FAIL  nobody can bypass it` | The applied ruleset has bypass actors, so the barrier is not absolute. | Remove them; the template has none. |
| `FAIL  check <name> is required and bound to an app` | The check is missing from the ruleset, or bound to another app. | Run the script again after a pull request with both checks merges. |
| `FAIL  auto-merge is enabled on the repository` | Queueing a merge will fail. | Enable auto-merge in the repository settings. |

## The review gate

These lines appear in the `Enforce the verdicts` step of the `claude-review` job. [`review-gate.md`](review-gate.md) explains each check.

| Line | Cause | Fix |
|---|---|---|
| `FAIL  <reviewer>: the run did not complete (conclusion=<value>)` | The action failed, for example because `CLAUDE_CODE_OAUTH_TOKEN` is missing or expired, or the turn limit was reached. | Check the secret, then re-run the job. |
| `FAIL  <reviewer>: no structured output was produced` or `the structured output is not valid JSON` | The reviewer ended without the answer the schema asks for. | Re-run the job. |
| `FAIL  <reviewer>: no file was reported as reviewed` | Nothing shows that the reviewer read the change. | Re-run the job. |
| `FAIL  <reviewer>: reported files outside this change: <files>` | The reviewer named files the diff does not touch. | Re-run the job. |
| `FAIL  <reviewer>: verdict pass with <n> blocking finding(s)` or `verdict fail with no blocking finding` | The answer contradicts itself. | Re-run the job. |
| `FAIL  <reviewer>: verdict is fail` | The reviewer found a blocking problem; the findings are printed above the line. | Fix them and push. The review runs again on the new commit. |

When the code reviewer blocks the change for reverting code it never touched, the branch was cut before the protected branch moved: rebuild it from the current protected branch.

## Guard refusals

A refused command is not run. The reason goes back to the model within the same turn, and is appended to `.claude/logs/guard-denials.jsonl`.

| Reason | Fix |
|---|---|
| `Committing on main is not allowed.` or `Pushing on main is not allowed.` | Work on a work branch. |
| `Committing on branch '<branch>' is not allowed. Use issue/<number>-<slug> to work an issue, or kit/<slug> to repair the kit itself.` | Switch to a branch with one of those shapes. |
| `The push targets main. GitHub performs the merge into main.` | Push the work branch; GitHub merges it. |
| `Force push is not allowed. Shared history is never rewritten.` or `A refspec prefixed with + forces the update.` | Push new commits on top instead. |
| `The push has no refspec and the upstream of <branch> is origin/main.` | Push with an explicit refspec, such as `git push origin <branch>`. |
| `This flag pushes refs beyond the current branch.` | Push the work branch explicitly. |
| `The push contains a variable or command substitution.` or `The git subcommand comes from an expansion.` | Write the literal value, so the guard can verify it. |
| `gh pr merge without --auto merges immediately.` or `gh pr merge --admin bypasses the required checks.` | Queue the merge with `gh pr merge --auto`. |
| `HOOK ERROR [<guard>]: <reason>` | The guard could not evaluate the request, and it refuses until it can decide. Fix what the reason names: install jq, restore `.claude/kit.vars` or its required keys, or, for a commit or a push, check out a branch instead of a detached HEAD. |

The reasons appear with the values from `.claude/kit.vars`; the table shows them with the template values.
