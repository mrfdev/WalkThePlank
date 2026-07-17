# Changelog

This changelog records source milestones. A listed feature is not production approval; release evidence and the Paper/live-data acceptance result belong in [checklist-walktheplank.md](checklist-walktheplank.md).

## [2.3.0-007] — 2026-07-17

Expected artifact: `1MB-WalkThePlank-v2.3.0-007-j25-26.2.jar`

### Command architecture

- Replaced legacy `PluginCommand`, `CommandExecutor`, and `TabCompleter` registration with a native Paper Brigadier tree registered through `JavaPlugin#getLifecycleManager` and `LifecycleEvents.COMMANDS`.
- Kept `plugin.yml` as the standard Bukkit plugin descriptor; no experimental `paper-plugin.yml` bootstrap is required.
- Added typed Paper/Brigadier online-player and UUID arguments, bounded integer limits, exact enum/literal branches, dynamic arena/season suggestions, greedy bounded season names, and literal confirmation nodes for destructive arena/reward decisions.
- Preserved `/walktheplank`, `/walk`, `/infinityparkour`, and `/infp`, the compatibility `version`, `open`, and root-level `reload`/`debug` forms, and runtime permission revalidation.
- Split the former 2,789-line command implementation into player, queue, arena, season/export, reward evidence, retained-run investigation, and SQLite/diagnostics modules with a small registration/reload coordinator and shared safety support.

### Guardrails

- SQLite remains the only backend. The command refactor adds no MySQL/MariaDB path, CMI database access, Vault coupling, or external profile lookup.
- Reward resolution and abandonment remain evidence-only and never dispatch or automatically replay an uncertain command.
- The historical all-time/Classic leaderboard query, rows, ranking, and top ten are unchanged.
- Experimental Paper Dialog and data-component APIs are not imported or used.

### Verification

- Added architecture regression tests locking lifecycle registration, typed arguments, explicit confirmations, module boundaries, the absence of legacy command interfaces, and the exclusion of experimental/external integration APIs.
- Build 007 still requires exact-artifact Paper 26.2 startup/reload/shutdown, command-tree/permission rehearsal, controlled scenarios, test-server synchronization, and human beta acceptance before event approval.

## [2.2.0-006] — 2026-07-17

Expected artifact: `1MB-WalkThePlank-v2.2.0-006-j25-26.2.jar`

### Runtime durability

- Added a bounded, plugin-owned, single-writer recovery executor. Restoration and player-recovery records now receive only immutable scalar values and cloned bytes; hashing, write, file fsync, atomic rename, directory fsync, and durable deletion run off the primary server thread.
- Added cancellation-safe one-shot preparation tickets. Pre-publication cancellation settles cleanly; cancellation racing a published append uses exact retryable deletion. Post-rename directory-fsync uncertainty and failed discard retain the published ownership record instead of claiming clean absence. A saturated/closing queue rejects without caller-thread fallback.
- Published immutable lock-free restoration and player-recovery journal views. Main-thread ownership, protection, doctor, placeholder, and health reads no longer wait behind the journal monitor while its writer is inside filesystem durability work.
- Added exclusive lifetime locks for the recovery and operations writers. Recovery ownership is acquired before journal open, temporary-file cleanup, or record loading; both locks remain held through a timed-out close and release automatically only when the corresponding writer actually terminates.
- Added exact arena leases `(run UUID, session generation)` and block leases `(run UUID, session generation, platform generation)`. Late completions cannot release or mutate a newer run's arena or coordinate.
- Administrative recovery now excludes the authoritative set of all arena-lease owners, including pending starts, active sessions, and quarantined cleanup; it cannot delete a pending-start write-ahead record before activation.
- Added primary-thread commit gates that revalidate the live session, player, permission, arena lease, block lease, exact original block state, and one-block structure fingerprint after durability succeeds and immediately before world mutation.

### Course pipeline and lifecycle

- Durable preparation for the next successor begins while the player traverses the current jump, and it is pre-placed when ready. Landing promotes only that exact successor—or rechecks after its completion if still pending—then captures the following candidate, restores/verifies the predecessor on the primary thread, and releases its lease only after off-thread journal deletion and directory sync complete successfully.
- Start activation now waits for player recovery plus base/target durability, then revalidates the unchanged captured player state and exact run ownership before preparation, teleport, or world mutation.
- Failed or cancelled start preparation retains the same-player admission gate plus exact arena/block leases until every published player/base/target record is durably discarded. Cleanup rejection or uncertainty is retried without allowing a stale callback to release newer ownership.
- Controlled session end, failed start, player recovery, administrative recovery, reload, and shutdown now use completion-driven recovery handoffs. Reload uses a worker barrier and returns to the primary thread; shutdown drains the writer before its final main-thread lease/quarantine gate and never waits for a worker callback that needs the server scheduler.
- Player-recovery deletion remains arena-quarantined until its exact durable completion returns. Successor and cleanup failures fail closed and preserve journal/lease evidence for retry or restart.

### Operations and configuration I/O

- Added an independent bounded `walktheplank-operations-writer` for audit, export, and configuration work, preventing log rotation or configuration commits from delaying recovery journals.
- Audit callers now prepare immutable bounded records; JSONL append, fsync, rotation, pruning, and directory sync occur off-thread with no `CallerRunsPolicy` fallback.
- Configuration reload and arena editing now use worker file capture, primary-thread Paper validation, worker compare-and-swap persistence, generation-CAS publication, and final disk verification.
- Arena edits require an empty queue and no active, pending, quarantined, or recovery-owned work. Rollback tokens retain the exact original bytes rather than trusting a mutable shared backup file, and disable reconciles any durably committed edit that did not reach final verified activation.
- `/walk admin doctor` now reports privacy-safe recovery/operations queue capacity, activity, acceptance, completion, failure, rejection, and lifecycle state plus pending player-recovery completions.

### Verification

- Added deterministic tests for FIFO execution, bounded saturation, caller-thread rejection, cancellation and directory-fsync races, retryable exact discard, defensive byte capture, lock-before-journal-open ordering, delayed-termination ownership release, exact lease generations, stale commit rejection, lock-free reads during journal mutation, worker independence, shutdown/close behavior, configuration generation races, and token-bound rollback.
- Updated test-only failpoint instrumentation for the new start-activation, block-restoration, and configuration-CAS boundaries. Production scenario isolation and independent instrumented-JAR reproducibility remain enforced.

### Operational notes and known limitations

- Build 006 requires a fresh clean-candidate run of the two-start Paper 26.2 profile, runtime-commit hard-kill recovery, startup/shutdown log assertions, test-server sync, and the real-player matrix. Build-005 results are historical and do not approve this artifact.
- Paper world, player, inventory, teleport, structure, and block access deliberately remains on the primary thread. Startup recovery still completes before normal arena admission; it is not part of the per-jump runtime pipeline.
- A bounded queue or durability failure ends/refuses the affected run, preserves any record that was published or whose post-rename commit is uncertain, and never infers evidence where a pre-publication write cleanly failed. Operators must investigate the doctor/log result and use the documented recovery workflow; the plugin never trades crash safety for a caller-thread write.

## [2.1.2-005] — 2026-07-17

Expected artifact: `1MB-WalkThePlank-v2.1.2-005-j25-26.2.jar`

### Added

- A separately compiled `WalkThePlank-ScenarioHarness` Paper plugin for disposable test profiles. It depends on InfinityParkour, requires the runner's exact nonce-bound generated root/marker/working-directory/data-path contract, refuses uninstrumented targets, emits deterministic PASS/FAIL/INFO/PENDING markers, and provides headless, lifecycle, restart-marker, failpoint, queue-contention, teleport, GUI, reconnect, and run-exit probes.
- A Java 25 Class-File API transformer that copies the production JAR to a clearly named test-only artifact and injects a bridge at reviewed durability/gameplay boundaries without changing the production source classes or deployment artifact.
- Twenty-four named failpoints covering player/restoration journal writes, fsyncs, renames, directory fsyncs and deletion; world-block placement/restoration; start/return teleports; score completion; reward claim/dispatch/outcome; CSV/JSON export renames; and configuration backup/candidate/disk/runtime commits. Every point supports `HALT`; 17 reversible/contained boundaries additionally support `THROW` and releasable `BLOCK`, while seven irreversible boundaries reject those actions.
- A disposable Paper 26.2 controlled-scenario profile and runner for two-start restart markers, PlaceholderAPI absent/present phases, `/walk admin reload`, terminal plugin disable/service-removal checks, fresh-JVM enable checks, scenario-marker assertions, broad plugin warning/error scans, and preserved per-phase logs.
- Real-player scenario capture for cancelled and retargeted teleports, disconnect/reconnect recovery, every session-end reason, same-tick two-player start/queue contention, and GUI left-click, shift-click, hotbar/number-key, double-click, creative-click, and drag observations. The stale-session command separately drives the production scheduler/revalidation path with a harmless sentinel and no fabricated inventory event or packet.
- Reflection tests that lock every gameplay/menu listener's event priority and `ignoreCancelled` contract.

### Security and test isolation

- `verifyReleaseJar` now rejects scenario packages, commands, manifest attributes, canaries, property prefixes, and all failpoint identifiers in the production JAR.
- `verifyProductionScenarioIsolation` proves the negative production result and also requires positive detection in both the isolated harness and instrumented target, verifies their distinct paths/identities, and rejects provided/runtime classes shaded into the harness.
- Test-only artifacts are written beneath `build/scenario-artifacts/`, never `build/libs/`, and are visibly prefixed `TEST-ONLY-`; the harness fails closed unless its explicit disposable-profile flag and injected bridge are present.
- The destructive runner rejects symlinked build/runtime/profile paths. Both the early startup failpoint bridge and later harness require the same safe nonce, bounded marker, real Paper working directory, generated `build/controlled-scenarios/` layout, and exact plugin data paths.
- Instrumentation verifies that all three exact database operation labels exist in both the production repository bytecode and injected router before it can emit a test artifact.
- A positive reproducibility control now builds the instrumented target twice and requires byte-for-byte identity; generated manifest timestamps are fixed instead of inheriting the wall clock.
- `controlledReleaseScenarios` orders the two-start profile before the automated runtime-commit exit-97/recovery profile, and test-server synchronization now depends on that clean-candidate scenario gate.

### Operational notes and known limitations

- Build-005 development artifacts passed the automated two-start PlaceholderAPI absent/present profile and the `config.after_runtime_commit` exit-97/recovery profile. Those runs validate the harness, but the exact clean committed candidate still needs its repeat run, freeze, Paper smoke, hash/reproducibility record, test-server sync, and human acceptance. Build-004 evidence remains historical and does not approve this artifact.
- Headless automation proves server/plugin lifecycle and deterministic contracts; it does not synthesize a genuine Minecraft client's inventory protocol, creative behavior, movement, disconnect, or simultaneous-player actions. Any scenario logged as `PENDING` still requires the named real-player test.
- A failpoint `HALT` terminates the JVM process; it does not emulate storage-device cache loss, host power loss, or filesystem corruption. Recovery evidence must record the actual SQLite `journal_mode`, `synchronous`, and `quick_check` values.
- CSV and JSON exports are individually atomically renamed, not committed as one pair. A halt after the CSV rename can leave an orphan CSV, and interrupted export/config temporary files do not currently have automatic startup cleanup. These outcomes must be preserved and classified during the hard-kill matrix.

## [2.1.1-004] — 2026-07-17

Expected artifact: `1MB-WalkThePlank-v2.1.1-004-j25-26.2.jar`

### Added

- Privacy-safe `/walk admin doctor` support report with build provenance, target/runtime details, hook and configured command-root status, queue/task health, restoration and reward-uncertainty counts, and an asynchronous read-only SQLite probe reporting `quick_check`, database/WAL sizes, migration-backup count, and latency.
- Explicit MiniMessage opt-in for trusted translation templates using the `minimessage:` prefix while retaining backward compatibility for every unmarked legacy ampersand template.

### Changed

- External active-run teleport decisions now occur at `HIGHEST`; `MONITOR` only observes the final cancellation/destination state, followed by run/attempt/world/destination verification on the next tick before cleanup commits.
- GUI authorization now binds the owner UUID, random nonce, monotonic generation, and exact inventory instance. Stale sessions are invalidated, duplicate clicks are suppressed, and only one action may be pending.
- GUI actions revalidate the current view, action identity, permissions, queue state, and authoritative gameplay state immediately before execution.
- Dynamic player, season, database, permission, and numeric values are inserted as literal Adventure components after trusted legacy or MiniMessage formatting is parsed.
- Bundled GUI titles begin the gradual MiniMessage migration; unmarked bundled/live values continue to deserialize with legacy `&` formatting.

### Security and hardening

- Duplicate or stale GUI clicks, cross-player inventory reuse, and superseded menu generations cannot authorize an action.
- Dynamic values cannot inject legacy formatting, MiniMessage tags, click/hover events, or other template behavior.
- Doctor output excludes player and season names, coordinates, filesystem paths, raw reward commands, credentials, SQL text, stack traces, and exception messages.

### Operational notes and known limitations

- Build 004 requires fresh Paper, GUI-abuse, teleport-ordering, formatting-injection, asynchronous-database, privacy, and full-stack acceptance. Build-003 smoke evidence does not approve this artifact.
- The asynchronous doctor probe is read-only operational evidence, not a backup, repair command, or substitute for stopped-server SQLite verification.
- Human in-game/destructive, reward-provider, hard-kill, and rollback acceptance remains pending.

## [2.1.0-003] — 2026-07-14

Expected artifact: `1MB-WalkThePlank-v2.1.0-003-j25-26.2.jar`

### Added

- Explicit schema-v2 event seasons with planned, active, closed, and archived states; one active season; monotonic transitions; and start-time season capture.
- Durable bounded run history with UUID ownership, release/end evidence, interruption recovery, and all-time/season score projection.
- Redacted reward plans and step ledger with deterministic IDs, idempotency keys, atomic dispatch claims, root/hash evidence, and terminal replay-protection tombstones.
- Atomic run completion, score projection, and eligible redacted reward-intent creation, with conflict rollback and exact-retry/no-retroactive-backfill rules.
- Staff reward commands to list/inspect plans, resolve one `UNKNOWN` step by explicit evidence, and reconcile an inspected `PENDING` plan by confirmed abandon without replay.
- Staff retained-run commands with a separate investigation permission, exact UUID/status/arena/season filters, redacted 1–100-row output, linked plan state, and operator-attributed query audit.
- FIFO player queue with readiness windows, reminders, cooldown, GUI/action-bar integration, and staff pause/resume/drain controls.
- Arena selection policies: `RANDOM`, `ROUND_ROBIN`, `LEAST_RECENTLY_USED`, and `PINNED` with safe fallback.
- Guarded in-game arena create/update/validate/remove workflow using atomic configuration replacement, backup, compare-before-write, and conditional rollback.
- UUID-only atomic CSV/JSON exports for all-time, active-season, and historical-season leaderboards.
- Persistent pre-mutation block-restoration journal with exact Paper structure snapshots, integrity evidence, startup recovery, conflict quarantine, stale-temp policy, and bounded record/aggregate sizes.
- Strict configuration version 2, aggregate validate-only reporting, safe configuration fingerprint, and deliberate warnings for legacy files missing `configVersion`.
- Nested arena/exit/reward-tier shape validation and an inclusive overlap check that rejects configurations capable of exceeding 100 durable reward steps for one score.
- Separate administrative permissions for arena, queue, season, export, reward, retained-run investigation, recover, validate, stop, open, and debug duties.
- Capacity, queue, active-run, active-season, and seasonal top-ten PlaceholderAPI values.
- Read-only primary-thread Bukkit services API and run-start, jump, run-end, personal-best, and reward-plan lifecycle events.
- Rotating fsynced JSONL audit trail and bounded health/diagnostic counters.
- Typed-holder GUI authorization, comprehensive inventory mutation cancellation, and bounded menu-open rate limiting.
- Safe custom-exit validation for support, two-block clearance, liquid/waterlogging/hazards, and separation from every protected arena volume.
- Required 1–32-entry canonical reward command-root allow-list with bundled `say`, `cmi`, `uf`, and `welcomes` defaults.
- Exclusive safe-sibling SQLite instance lock that rejects a second repository targeting the same database and releases only after a clean drain or process exit.
- Privacy-bounded, fsynced player-recovery records written before state mutation or arena teleport, with exact retained-run ownership verification and fail-closed reconnect quarantine.
- Gradle dependency locks and SHA-256 verification metadata, a pinned wrapper distribution checksum, reviewed commit pins for CI actions, and weekly Gradle/Actions update proposals.

### Changed

- Modernized the build to Java 25, Paper API 26.2 build 60 beta, Gradle 9.6.1, Shadow 9.5.1, PlaceholderAPI 2.12.3, SQLite JDBC 3.53.2.0, and strict `-Xlint:all -Werror` compilation.
- Retained Bukkit plugin name `InfinityParkour` and its data directory while publishing the 1MB WalkThePlank artifact identity.
- Kept PlaceholderAPI as the only optional Java/API integration, with registration failure contained. Added targeted `CMI`, `UltimateFireworks`, and `PyroWelcomesPro` soft-dependency startup hints for synchronous validation of configured reward roots; CMILib, Vault, and PyroLib are not WalkThePlank dependencies.
- Made SQLite the only supported backend and standalone runtime service. No MySQL/MariaDB driver or code path is included.
- Made UUID the sole owner of player progress. Names are last-known display data; unresolved legacy rows are never claimed by matching a username.
- Protected active/uncertain reward runs from retention and preserved compact replay-prevention evidence when an old terminal full ledger is pruned.
- Made accepted database operations settle deterministically when close rejects work that never started.
- Added operator-attributed arena-edit, restoration-retry, and queue pause/resume/drain audit evidence plus restoration retry outcome metrics.
- Added a per-player reward completion barrier so a delayed reward cannot overlap the player's next parkour run.
- Queue pause now freezes existing readiness deadlines and prevents claim consumption while preserving FIFO positions until resume.
- Revalidates play permission during an active run, normalizes residual velocity/walk speed for fairness, and restores the captured walk speed afterward.
- Revalidates exits at use time, falls back through the captured return and safe world spawn, and quarantines an arena when every return teleport is rejected.
- Publishes active game/queue state as immutable snapshots so PlaceholderAPI callbacks do not traverse mutable Bukkit-thread collections.
- Uses one atomically published validated configuration bundle; a failed reload apply restores the previous bundle, and reload refuses to change arenas while cleanup evidence remains unresolved.
- Validates last-known names against vanilla Java grammar and scores as bounded non-negative integers on every persistence boundary; CSV export also neutralizes spreadsheet formula prefixes.
- Protects unresolved `UNKNOWN` runs from retention instead of guessing a terminal outcome.
- Separates active write-ahead player evidence from crash-recovery quarantine, and commits external-teleport cleanup only after a next-tick destination check.
- Contains failures per session/pending start during reload, shutdown, and the recurring fairness/timeout sweep so later cleanup and future sweeps continue.

### Security and hardening

- Rejects unsafe/symlinked database and configuration paths, unsupported schema/index/foreign-key definitions, malformed or duplicate UUID identities, and unsupported future schema versions.
- Rejects unsafe platform materials, including known stateful/workstation suffix families without deprecated Paper calls; arena overlaps/bounds/headroom problems; malformed or over-100-step reward plans; unsafe command roots; invalid permission collisions; and invalid translation/GUI placeholder types. The live `JACK_O_LANTERN` palette remains valid.
- Rejects configured reward commands outside `rewards.allowedCommandRoots` even when rewards are disabled, and repeats the allow-list check at runtime before command-map lookup or dispatch.
- Journals before every course block change, verifies restoration, and refuses to overwrite a third-party conflict or silently discard malformed evidence.
- Stores no raw reward command text in SQLite, audit, diagnostics, export, or public API.
- Prevents generic GUI-title/lore/slot spoofing from authorizing actions and exercises explicit confirmation for destructive/operator reward actions.
- Prevents active runners from damaging others, moving items through pickup/drop/inventory actions, swapping hands, or interacting with entities during the isolated run.
- Keeps Paper, Bukkit, PlaceholderAPI, MySQL/MariaDB, and live server/database content out of the shaded artifact through a release verification gate.
- Rejects non-default movement-speed bases and every modifier except Paper's exact vanilla sprint modifier, rechecks active players every second, and blocks held-slot switching during runs without rewriting equipment.
- Quarantines unresolved player-recovery ownership and all affected arena volumes; unreadable evidence with unknown arena ownership conservatively withholds every arena.
- Makes reward-barrier submission, callbacks, durable finalization, and release diagnostic-independent so an exception cannot silently strand a player's next run.
- Reports partial startup as aborted and never emits the clean-restoration success claim for an enable that did not finish.
- Rejects configured particles that require typed payload data because gameplay intentionally uses Paper's no-data spawn overload.

### Operational notes and known limitations

- Adds exact Git commit/dirty provenance and a clean-source freeze gate. Final candidate size, SHA-256, test count, reproducibility, and smoke evidence are stored in the annotated RC tag and operator archive after the source commit, avoiding a commit/checksum cycle. Human in-game/destructive acceptance remains pending.
- Paper 26.2 is a beta target and needs production-like server rehearsal.
- Console-command execution is not transactional. A crash after durable `DISPATCHING` becomes `UNKNOWN`; it is never automatically replayed.
- A wholly `PENDING` plan survives restart. Staff must list, inspect, and explicitly abandon it; there is intentionally no in-game raw-command resume path.
- Missing/disallowed roots and contained ordinary command-map preflight failures store a redacted non-executable plan atomically and then finalize it without dispatch. A reward-intent construction failure that escapes before any request exists completes the run without a plan and records the failure; neither path replays work.
- A SQLite JDBC call already running may outlive an unsuccessful repository close if it ignores interruption, although queued accepted operations are settled deterministically.
- Player snapshots and pending returns now have a separate privacy-bounded, atomic recovery journal written before state mutation/arena teleport. Reconnect recovery requires exact retained-run ownership, restores state before choosing a live-safe destination outside every arena, and retains malformed, orphaned, or incomplete evidence fail-closed.
- Signs, containers, tile/PDC restoration, GUI abuse, queue concurrency, dependencies as configured reward providers, and kill/restart behavior still require the final human server checklist.
- No approved license file is present. Treat this as a private/custom build unless relevant rights holders authorize redistribution.

## Pre-2.1 modernization history

The v2.0.x work established the Java 25/Paper 26.2 Gradle modernization, 1MB artifact naming, SQLite-only standalone packaging, modern command/permission structure, UUID-aware leaderboard migration, GUI/gameplay hardening, and the initial beta checklist. Build 003 superseded those development artifacts; do not reuse their hashes, sizes, test counts, or server-smoke evidence as build-003 evidence.
