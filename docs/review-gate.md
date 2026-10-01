# Review gate and required checks

The ruleset on the protected branch requires two status checks. Each one is a job in a workflow under [`kit/workflows/`](../kit/workflows), installed into `.github/workflows/`, and each one is bound to the GitHub Actions app so that nobody else can publish its status.

| Check | Workflow | What it proves |
|---|---|---|
| `kit-checks` | `kit-ci.yml` | The kit installs, the hooks pass their battery, the installation is consistent, and the project's format check and tests pass |
| `claude-review` | `kit-review.yml` | Two reviewers judged the change, and a shell script accepted both verdicts |

The job names are the status-check contexts the ruleset requires. Renaming a job removes the merge gate without any error.

## Contents

- [kit-checks](#kit-checks)
- [claude-review](#claude-review)
- [The verdict](#the-verdict)
- [What the reviewers look for](#what-the-reviewers-look-for)
- [Local and CI reviews](#local-and-ci-reviews)
- [Design rules](#design-rules)
- [Binding the checks to their app](#binding-the-checks-to-their-app)

## kit-checks

Runs on every pull request against the protected branch.

1. **Read the project variables** from `.claude/kit.vars`: the JDK version, the format check and the test command.
2. **Set up the JDK** (Temurin, with the Maven cache) when `KIT_JAVA_VERSION` is set.
3. **Install the kit** with `bash kit/install.sh`. This proves the installer works and generates the hooks, which are not versioned.
4. **Run the guard battery** with `bash kit/test-guards.sh`.
5. **Check consistency** with `bash kit/doctor.sh`, with no fallback: a drift between `kit/` and an installed copy blocks the merge.
6. **Run the format check, the spec check and the tests** from `KIT_FORMAT_CHECK_CMD`, `KIT_SPEC_CHECK_CMD` and `KIT_TEST_CMD`, each skipped when empty.

## claude-review

Runs when a pull request against the protected branch is opened or receives new commits. The job has read-only access to the contents, the pull requests and the issues, and times out after 20 minutes.

1. **Check out** the full history.
2. **Resolve the issue.** On a branch shaped `issue/<number>-<slug>`, the number is taken from the branch name; any other branch has no issue.
3. **List the changed files** with `git diff --name-only <base> <head>` into `changed-files.txt`.
4. **Code review.** `anthropics/claude-code-action` runs with a prompt that points at `.claude/agents/code-reviewer.md`, up to 80 turns, and only the `Read`, `Grep`, `Glob` and `Bash` tools.
5. **Acceptance audit**, only when the branch has an issue. Same action, pointing at `.claude/agents/acceptance-auditor.md` and at the issue through `gh issue view <number>`, up to 80 turns. The turns a reviewer uses vary between runs and grow with the size of the change, and running out of them fails the step, so both reviewers get a wide limit.
6. **Enforce the verdicts.** Always runs, and decides the job's exit code.

Both reviewers must answer with structured output of this shape, which a JSON schema in the workflow enforces:

```json
{
  "verdict": "pass | fail",
  "files_reviewed": ["files of this diff the reviewer read"],
  "files_consulted": ["anything else it read, such as documentation"],
  "blocking_findings": [{ "file": "path", "description": "what is wrong" }],
  "summary": "one paragraph"
}
```

## The verdict

The `Enforce the verdicts` step judges each reviewer in the shell. A reviewer passes only when every check below holds; the first one that fails prints its line and refuses.

| Check | Refusal printed by the step |
|---|---|
| The action completed | `FAIL <reviewer>: the run did not complete (conclusion=…)` |
| It produced structured output | `FAIL <reviewer>: no structured output was produced` |
| The output is valid JSON | `FAIL <reviewer>: the structured output is not valid JSON` |
| It names at least one reviewed file | `FAIL <reviewer>: no file was reported as reviewed` |
| Every reviewed file is in `changed-files.txt`, once a leading `/` or `./` is dropped | `FAIL <reviewer>: reported files outside this change: …` |
| `pass` comes with no blocking finding | `FAIL <reviewer>: verdict pass with N blocking finding(s)` |
| `fail` comes with at least one blocking finding | `FAIL <reviewer>: verdict fail with no blocking finding` |
| The verdict is `pass` | `FAIL <reviewer>: verdict is fail` |

Every failure mode lands on the refusing side: a review that did not run never passes. On a branch with no issue, the step prints `PASS  acceptance audit: no issue on this branch, not applicable`, and only the code review decides.

## What the reviewers look for

**The code reviewer** reviews only the files the diff touches, without knowing why any decision was made. A finding is blocking when:

- the change can throw or corrupt state on an input the code already accepts elsewhere;
- an error is swallowed: caught, and neither handled nor propagated;
- a resource is opened and not closed on every path;
- a secret, credential, token or personal datum appears in source, in a log line or in a fixture;
- a public signature changed and a caller in the repository was not updated;
- a test was deleted, disabled or had its assertion weakened, and the diff does not say why;
- code was added that no test and no caller reaches.

Style, naming and formatting are advisory; the format check in `kit-checks` covers formatting.

**The acceptance auditor** ignores code quality and judges each acceptance criterion of the issue on its own. A criterion is met only with evidence the auditor observed: a command it ran and whose output it read, or a test it found and confirmed exercises that criterion. Reading code that appears to implement a criterion is not evidence. A criterion whose test was deleted, disabled or weakened in the change is unmet, an ambiguous criterion is reported as ambiguous, and the verdict is `pass` only when every criterion is met. With `KIT_TEST_CMD` empty, every criterion that depends on the tests is unverifiable.

## Local and CI reviews

The same two prompts run twice for an issue worked by the runner:

| Where | When | Output | Purpose |
|---|---|---|---|
| Subagents inside the headless session | Before the final commit | A `### VERDICT` block returned to the session | The session fixes blocking findings before it pushes |
| `claude-review` in GitHub Actions | On the pull request | Structured output in the schema above | The gate: the only verdict that counts |

## Design rules

- **One job for both reviewers.** A skipped job reports success to a required check, so whether the audit applies is decided by the shell, never by an `if:` on a job.
- **Values reach the shell through `env:`.** A `${{ }}` inside `run:` is substituted before bash parses the script, so an edited value would become script instead of an argument.
- **The criteria come from the protected branch.** The action restores `.claude` from the protected branch, because a pull request head is untrusted, so a pull request cannot weaken the criteria it is judged by. This is also why `.claude/agents` is versioned.
- **The reviewed files are checked against the diff.** Comparing what a reviewer claims to have read with `git diff` turns "did the review happen" from a claim of the model into a fact the shell can check.

## Binding the checks to their app

Without a binding, anyone with write access to the repository can set the state of any status check, which would let the agent publish its own green verdict. [`kit/github/apply-protection.sh`](../kit/github/apply-protection.sh) closes that:

1. It reads the check runs of the head commit of the last merged pull request and takes the id of the app that published them, when exactly one app did.
2. It sets that id as the `integration_id` of both required checks in a copy of [`kit/github/ruleset-main.json`](../kit/github/ruleset-main.json), and creates or updates the `protect-main` ruleset from it.
3. It enables auto-merge on the repository.
4. It reads the ruleset back and verifies it: active, no bypass actors, the pull request, deletion and non-fast-forward rules present, each check bound to the app, auto-merge enabled.

When no app id can be measured, because no pull request has merged yet or because more than one app published check runs on that commit, the script leaves the required checks out: requiring a check that never reports would block every pull request. Running the script again after the first merge adds them.
