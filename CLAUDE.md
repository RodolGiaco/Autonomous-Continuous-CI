# CLAUDE.md

This repository is the source of **gated-agent-workflow**, a kit that lets Claude Code work GitHub issues unattended while the merge into the protected branch stays behind server-side checks. `kit/` is the product. Every repository that uses the kit holds a copy of `kit/` made from here, so a defect found in any of them is fixed here, in `kit/`, and copied out again.

`src/` holds the reference application the cycle builds one issue at a time: an order management API in Spring Boot.

## Running commands

Permission rules are evaluated per subcommand: in `a && b` or `a | b`, every part must match a rule on its own. A denial of a compound command usually means one part is not allowed, not the part you were aiming at. Run one command per call instead of chaining, and read the file rather than piping it through `tail`.

## The kit

### Map

| Source | Installed as | Versioned | Role |
|---|---|---|---|
| `kit/hooks/guard-push.sh`, `guard-commit.sh`, `guard-merge.sh`, `guard-common.sh` | `.claude/hooks/` | Source only | PreToolUse guards on push, commit and merge |
| `kit/hooks/session-context.sh` | `.claude/hooks/` | Source only | SessionStart facts: branch policy and the issue in progress |
| `kit/settings.json` | `.claude/settings.json` | Source only | Permission rules and hook registration |
| `kit/agents/*.md` | `.claude/agents/` | Both | Prompts of the code reviewer and the acceptance auditor |
| `kit/workflows/*.yml` | `.github/workflows/` | Both | The `kit-checks` and `claude-review` required checks |
| `kit/kit.vars.example` | `.claude/kit.vars`, created once | Both | The only file with project-specific values |
| `kit/gitignore.fragment` | A block in `.gitignore`, appended once | Both | Ignores what the kit generates |
| `kit/github/ruleset-main.json` | The `protect-main` ruleset on GitHub | Source | The server barrier, applied by `kit/github/apply-protection.sh` |
| `kit/run-issue.sh` | — | Source | Works one issue end to end |
| `kit/install.sh`, `kit/doctor.sh`, `kit/test-guards.sh` | — | Source | Install, verify and test the kit |

Runtime files are never versioned: `.claude/kit-state/current-issue.json` holds the issue the runner is working, read by the SessionStart hook and the acceptance auditor; `.claude/logs/guard-denials.jsonl` records every guard refusal; `.env.local` holds the local variables that `.env.example` documents.

The kit is documented in `README.md` and its translation `README.es.md`, in `kit/README.md` and in `docs/`. A change to the behaviour of the kit updates them in the same pull request.

### Invariants

Breaking one of these either opens the barrier or stops the cycle, often without an error.

- **Edit `kit/`, never an installed copy.** `bash kit/install.sh` regenerates `.claude/hooks` and `.claude/settings.json` and copies the agents and workflows. `doctor.sh` fails on any drift, and CI runs it.
- **The job names are the gate.** `kit-checks` and `claude-review` are the status-check contexts the ruleset requires. Renaming a job removes the merge gate.
- **No `if:` on a job of a required workflow.** A skipped job reports success to a required check; decide inside a shell step.
- **No `${{ }}` inside `run:`.** Values from a workflow context reach the shell through `env:`, because interpolation happens before bash parses the script.
- **The shell owns every verdict.** Pass or fail is decided in the `Enforce the verdicts` step, and a missing or inconsistent answer is a refusal. A model's output never sets an exit code by itself.
- **Hooks fail closed.** A guard that cannot evaluate a request calls `fail_closed`: exit 2, reason on stderr. A refusal calls `deny`: a `permissionDecision: deny` JSON on stdout, exit 0, and one line in the refusal log. No error path exits 0 without a decision.
- **Guards parse by position and decide on repository state**, never on a keyword search over the command text. A value they cannot resolve statically, such as an expansion, is refused.
- **The SessionStart hook never blocks and states facts.** Text phrased as out-of-band instructions can trip the model's prompt-injection defences.
- **`kit.vars` is sourced, not parsed.** Values with spaces are quoted, and no other file under `kit/` holds a project-specific value.
- **The runner reports only what it observed.** It reads `permission_denials` rather than the exit code, and reads back every mutation before counting it as applied.
- **Deny rules are for absolutes.** A condition that depends on state belongs in a hook, and every deny entry is justified in `kit/README.md`.
- **`bypass_actors` stays empty** in `kit/github/ruleset-main.json`.

### Fixing a defect reported from another repository

1. Collect the facts there: its `.claude/kit.vars`, the exact command or state that misbehaved, its `.claude/logs/guard-denials.jsonl`, and the output of `bash kit/doctor.sh`.
2. Reproduce it here. For a hook, add the failing case to `kit/test-guards.sh` first, then run `bash kit/install.sh` and `bash kit/test-guards.sh` and watch it fail.
3. Fix it in `kit/`, and run the verification below until it passes.
4. Commit on a `kit/<slug>` branch, or on `issue/<number>-<slug>` when there is an issue, and open a pull request. GitHub merges it once both required checks pass.
5. Update the other repository: copy `kit/` into it again and run `bash kit/install.sh` there.

`docs/troubleshooting.md` maps every failure message of the kit to its cause. `docs/hooks.md` and `docs/review-gate.md` describe the hooks and the required checks in depth.

### Verifying the kit

```bash
bash kit/install.sh         # regenerate the installed copies
bash kit/test-guards.sh     # 63 hook cases, offline
bash kit/doctor.sh          # installation, consistency, server ruleset
bash -n kit/run-issue.sh    # syntax of every script you edited
```

The runner and the workflows have no offline test. A change to either is verified by a real run: an issue, `bash kit/run-issue.sh <number>`, and the checks on the pull request it opens.

### Branches and commits

- `main` changes only through a pull request that GitHub merges. The guards refuse commits and pushes from any branch other than `issue/<number>-<slug>` and `kit/<slug>`.
- Conventional commits, imperative mood, lowercase, no trailing period, at most 72 characters:

  ```
  feat(catalog): add the product catalog module

  Optional body explaining why.

  Refs #39
  ```

  Types: `feat` `fix` `chore` `docs` `test` `refactor` `perf` `ci` `build`. The scope names the part of the repository: `kit`, `catalog`, `order`. `Refs #N` goes in commits, `Closes #N` in the pull request description.

## The reference application

### Stack

Java 21 · Spring Boot 4.1 (Web MVC, Data JPA, Bean Validation, Actuator) · PostgreSQL with Flyway · Maven (`./mvnw`) · JUnit Jupiter 6, Mockito, Testcontainers · Spotless with google-java-format

Modern APIs only:

| Legacy (Boot 2.x) | Use instead |
|---|---|
| `javax.*` | `jakarta.*` |
| Field `@Autowired` | Constructor injection, `final` fields |
| `RestTemplate` | `RestClient` |
| `@MockBean` | `@MockitoBean` |
| Lombok `@Data` DTOs | `record` |

### Layout

Every module follows the same hexagonal layout under `io.github.rodolgiaco.oms`:

```
<module>/api/                          controller, request and response records, API mapper
<module>/application/                  service implementing the use cases, business exceptions
<module>/application/port/in/          one interface per use case, and its command
<module>/application/port/out/         the repository port
<module>/domain/                       model and invariants, free of Spring and JPA
<module>/infrastructure/persistence/   JPA entities, Spring Data repository, adapter, mapper
web/                                   ApiExceptionHandler, which turns every error into a ProblemDetail
```

The modules are `order` and `catalog`. `docs/reference-application.md` documents the endpoints and the error format.

### Rules

- The controller holds only use case interfaces, and the service only its repository port. `*LayerDependencyTest` checks both.
- Domain classes carry no JPA or Hibernate annotation. `*DomainPersistenceIndependenceTest` checks it.
- Entities and domain objects never cross the API boundary: records in, records out, converted by the API mapper.
- `@Transactional` lives on the persistence adapter, with `readOnly = true` on queries. A lazy association is mapped inside the transaction that loaded it.
- Every error is a `ProblemDetail` (RFC 9457) from `ApiExceptionHandler`: 400 for invalid input, 404 for an unknown identifier, 409 for a conflict. A new business exception gets its handler there.
- Flyway owns the schema, and Hibernate only validates it (`ddl-auto=validate`). The orders tables migrate from `db/migration` into the default schema; the catalog migrates from `db/catalog` into the `catalog` schema, with its own history, through `CatalogSchemaConfiguration`. An applied migration is never edited: a change is a new version.
- Configuration comes from the environment: `OMS_DATABASE_URL`, `OMS_DATABASE_USERNAME` and `OMS_DATABASE_PASSWORD`, which has no default.

### Javadoc

Every bean documents why it is a bean:

1. Which stereotype it uses and why that one.
2. Its scope and what that implies (singleton → no mutable state).
3. Its dependencies and how they are injected.
4. Its role in the request flow.

Public controller and service methods document `@param`, `@return` and `@throws`; a method that implements a use case inherits them from its interface. Non-obvious Spring annotations (`@Transactional(readOnly = true)`, `@Lazy`, `@Primary`, `@Qualifier`) state why they are there.

### Tests

Written alongside the code:

| What | How |
|---|---|
| Domain model | Plain unit tests covering every invariant |
| Service | Plain unit tests over an in-memory implementation of the repository port |
| Controller | `@WebMvcTest` with the use cases replaced by `@MockitoBean` |
| Adapter, schema, end-to-end API | `@SpringBootTest` with `@Import(TestcontainersConfiguration.class)`, against a real PostgreSQL. MockMvc is built from the context, so these classes share one cached context and one container |
| Layer rules | Reflection tests over fields, constructors and annotations |

Assertions use JUnit's `Assertions`. `./mvnw verify` runs everything and needs Docker. `./mvnw spotless:apply` formats the code, and CI runs `mvn -B -q spotless:check`.

### Specs

Every issue is worked as one OpenSpec change. The issue body stays the authority: the change records how it was read, and `openspec/specs` holds the behaviour that is current.

- Name the change after the branch, with `/` replaced by `-`: `issue/48-cancel-an-order` becomes `issue-48-cancel-an-order`.
- Create it from the issue body before any code, with the `openspec-propose` skill. The issue's out of scope list goes into the proposal.
- Tick each task in `tasks.md` as it is done.
- A delta that creates a capability carries a `## Purpose` of at least 50 characters: archive copies it into the new spec, and strict validation rejects a placeholder or a shorter one.
- A change that only refactors or touches documentation sets `skip_specs: true` in its `.openspec.yaml`.
- Before the final commit, archive the change with `openspec archive <name> --yes` and run `bash scripts/spec-check.sh` until it passes.
