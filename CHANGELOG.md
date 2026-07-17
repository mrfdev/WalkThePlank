# Changelog

This changelog records source milestones. A listed feature is not production approval; release evidence and the Paper/live-data acceptance result belong in [checklist-walktheplank.md](checklist-walktheplank.md).

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

The v2.0.x work established the Java 25/Paper 26.2 Gradle modernization, 1MB artifact naming, SQLite-only standalone packaging, modern command/permission structure, UUID-aware leaderboard migration, GUI/gameplay hardening, and the initial beta checklist. Build 003 supersedes those development artifacts; do not reuse their hashes, sizes, test counts, or server-smoke evidence for this release.
