# Changelog

This changelog records source milestones. A listed feature is not production approval; release evidence and the Paper/live-data acceptance result belong in [checklist-walktheplank.md](checklist-walktheplank.md).

## [2.6.0-022] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.6.0-022-j25-26.2.jar`

### Seasonal appearance presets

- Added validated, named appearance presets that atomically select the platform palette, placement particle, visibility, and count without changing scoring, reachability, rewards, or the historical Classic leaderboard.
- Bundled `default`, `summer`, `halloween`, `winter`, and `valentine` presets use safe full blocks with exact untyped Paper 26.2 particles. Operators can define up to 32 lowercase presets with up to 16 blocks each.
- Retained `theme.active: custom` as the backward-compatible default. Historical live files continue using their top-level `parkourBlocks` and `particle` values until an operator deliberately selects a preset.
- Existing UUID-owned particle preferences remain authoritative: `full`, `reduced`, and `off` are applied to the selected preset, so seasonal configuration cannot override player accessibility choices.
- Configuration validation and reload fail closed for missing presets, unsafe blocks, typed/unsupported particles, invalid counts, malformed names, or unknown preset keys. The safe configuration fingerprint and `/walk debug config` now include the effective theme.

## [2.5.4-021] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.5.4-021-j25-26.2.jar`

### Precise landing sampling

- Changed the Paper movement listener to inspect precise-position changes, including a landing that remains inside one block coordinate, while continuing to ignore orientation-only packets.
- Added focused sampling regressions for within-block movement and look-only events.
- A real-player score-9 run confirmed sequential scoring, immediate departed-platform restoration, exact two-platform rotation, milestone/combo feedback, and clean final restoration.

## [2.5.3-020] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.5.3-020-j25-26.2.jar`

### Exact air restoration

- Added a primary-thread restoration strategy that writes captured `AIR`, `CAVE_AIR`, and `VOID_AIR` directly through Paper's block-data API.
- Retained Structure-based restoration for non-air blocks so tile state, PDC, and unknown metadata remain preserved.
- Extended failpoint instrumentation and regression coverage for both restoration strategies.

## [2.5.2-019] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.5.2-019-j25-26.2.jar`

### Historical GUI-default inheritance

- Fixed inherited translation defaults so historical live `translations.yml` files materialize the new player-head, back, and close menu sections in memory.
- Retained operator-customized translations and avoided rewriting the historical file.
- Added a regression proving a legacy translation file can open the complete six-action menu.

## [2.5.1-018] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.5.1-018-j25-26.2.jar`

### Two-platform gameplay invariant

- Fixed the durability pipeline placing its already-prepared successor before the player landed. A run now exposes exactly the original two-platform window: the platform supporting the player and one destination, never an additional preview platform.
- The next restoration record is still captured and fsynced on the bounded recovery worker while the player traverses the current jump, but successful durability completion remains hidden until an exact grounded landing.
- Landing revalidates the current run/session generations, arena and block leases, player online/death/permission state, and the exact prepared owner immediately before world mutation. The departed platform is restored first and exactly one replacement destination is then placed on the primary thread.
- The claimed successor is registered with normal failure cleanup before either world mutation, so a restore or placement exception cannot orphan its journal record or block lease.
- Added a focused successor-state-machine test suite covering pending, durable-hidden, exact consume, stale callback, duplicate callback, and run-end abandonment paths.

## [2.5.0-017] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.5.0-017-j25-26.2.jar`

### Player and navigation controls

- Added the opening player's textured `PLAYER_HEAD` at bottom-left. Its non-italic pastel tooltip uses one immutable score snapshot to show the Classic personal best, rank/total and percentile; no-score and no-permission states have dedicated bounded lore.
- Clicking the head uses the same permission-checked behavior as `/walk stats`. The exact live permission is revalidated immediately before execution by the existing owner/nonce/generation/inventory-bound action gate.
- Added a bottom-right `BARRIER` close action and an adjacent `ARROW` that closes WalkThePlank before executing `/menu` as the player. A missing, denied or failed external menu command produces a bounded translated fallback instead of an unhandled GUI exception.
- Player-head creation uses the online player's already-loaded Paper profile through the supported Paper 26.2 `SkullMeta#setPlayerProfile` API; it performs no username lookup or network request.
- New menu sections are inherited from bundled translation defaults without rewriting historical live files. Validation requires `PLAYER_HEAD` for the profile item, restricts every dynamic lore token by path, and keeps unsupported/malformed placeholders fail-closed.

## [2.4.7-016] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.7-016-j25-26.2.jar`

### Configuration-aware tutorial

- Replaced “glowing platform” with the literal `{{platformBlock}}` GUI token. The tutorial now names the configured parkour material, such as “jack o'lantern” or “emerald block,” instead of assuming that every target glows.
- One to three distinct configured materials receive a bounded natural-language label; longer lists use “configured platform” so an operator cannot create an excessively wide tooltip.
- The material label is inserted after trusted MiniMessage parsing, retaining the plugin's formatting-injection boundary and recursive non-italic styling.
- Translation validation explicitly allows only `{{platformBlock}}` in tutorial lore and continues to reject unsupported or malformed tokens.
- The exact build-014/015 bundled tutorial is upgraded in memory to the dynamic text, alongside the original historical live translation. Genuinely customized operator lore remains authoritative and no translation file is rewritten.

## [2.4.6-015] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.6-015-j25-26.2.jar`

### Play-tooltip proofreading

- Replaced the final legacy-sounding Play description with concise instructions: start an endless run, join the queue automatically when all arenas are busy, click again when the turn is ready, and leave safely with `/walk leave`.
- Split the queue explanation into readable tooltip-width lines, highlighted the ready instruction in pastel green, retained the pink/gold exit treatment, and changed the final action from “Click to play” to the clearer “Click to begin.”
- The recursive non-italic renderer and exact in-memory legacy GUI upgrade from build 014 remain unchanged, so the corrected text appears for the historical live file without rewriting it.
- The operator approved the visual treatment of all three menu icons. Six rapid same-arena build-014 client runs also completed with only the expected bounded cleanup messages and no lifecycle failure, quarantine, or blocked next start.

## [2.4.5-014] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.5-014-j25-26.2.jar`

### Modern 1MB tooltips and idempotent cleanup

- Rewrote the bundled tutorial, play, and top-ten tooltips with concise proofread wording and the shared readable 1MB pastel palette: pale blue names, white/soft-gray body copy, pink warnings and labels, and gold click actions.
- GUI display names and every nested lore component now force Adventure `TextDecoration.ITALIC` off after template parsing and literal placeholder replacement. Legacy text, MiniMessage, leaderboard rows, and an explicitly italic nested component cannot reintroduce Minecraft's old default tooltip italics.
- Added a narrow in-memory compatibility upgrade for the exact tutorial/play/scoreboard definitions and window title shipped in the historical live `translations.yml`. It adopts the modern bundled text without modifying the file; any genuinely customized item definition remains authoritative.
- Real-player build-013 testing completed five consecutive starts—including forward/backward/sprinting/jumping—with no `PLAYER_STATE_*` refusal and no velocity loop. One initial non-zero velocity was zeroed once as designed.
- The same test exposed two stale block-cleanup callbacks after a later run acquired the coordinate. Cleanup completion is now idempotent: an already released lease is accepted and a newer exact owner is preserved without a false lifecycle failure or mutation.

## [2.4.4-013] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.4-013-j25-26.2.jar`

### Paper build 62 and real-player activation

- Updated the exact compile dependency and locked verification metadata to official Paper API `26.2.build.62-beta`. `api-version: 26.2` continues to reject older Minecraft lines, and startup now verifies Paper's supported `ServerBuildInfo` version/build before opening plugin data; Paper below 26.2 build 62 or a runtime without a build number fails closed.
- Fixed forward walking starts failing as `PLAYER_STATE_CHANGED` while backward re-entry worked. Post-durability revalidation now permits ordinary sub-block movement, look-direction changes, saturation, and exhaustion while the pending-start listener still prevents cross-block/world movement and critical health/food/walk-speed/flight/collision changes.
- Expanded snapshot refusals into exact `PLAYER_STATE_<condition>` console and audit codes.
- Reworked external velocity defense. A non-zero server-applied velocity during an active run is replaced with zero without cancelling Paper's cause-less `PlayerVelocityEvent`; the plugin's own zero-velocity start normalization is ignored. This prevents the cancellation loop that produced hundreds of false anomalies and interfered with normal gameplay.
- Expected asynchronous journal cleanup now logs an informational arena reservation/settlement transition instead of a false `ERROR`; actual cleanup failures remain severe and quarantined.
- Diagnostics now distinguish `Paper 26.2 build 62 or newer` from the exact API coordinate and show the runtime build explicitly.

## [2.4.3-012] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.3-012-j25-26.2.jar`

### Durable start activation and settings permission

- Fixed the final run-activation gate rejecting every exact player-recovery record immediately after it was durably published. Pre-write admission still requires no retained recovery record; post-write activation now requires the exact healthy player/run/arena-owned record returned by the durability worker and published by the journal.
- Replaced the aggregate `POST_DURABILITY_INELIGIBLE` result with stable condition-level rejection codes. Durability-stage refusals now include the exact reason in both the chained audit and the server console without logging player names, locations, filesystem paths, commands, or credentials.
- Added regression coverage for exact post-durability record ownership, missing/mismatched/unhealthy evidence, and every dynamic activation rejection branch.
- Removed `infinityparkour.preferences` from the default-true player parent. `/walk settings` remains guarded at Brigadier visibility and execution time and now requires an explicit `infinityparkour.preferences` grant; the operator-default admin parent retains it.

## [2.4.2-011] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.2-011-j25-26.2.jar`

### Paper 26.2 movement admission

- Fixed a Paper 26.2 admission regression that compared a player's normal `MOVEMENT_SPEED` base with the attribute registry's global default. Paper documents that registry value as non-contextual; admission now reads the unmodifiable player-type defaults through `EntityType#getDefaultAttributes`.
- Corrected the exact vanilla sprint modifier shape to Paper's `MULTIPLY_SCALAR_1` representation of Minecraft's `ADD_MULTIPLIED_TOTAL`, so legitimate sprinting remains eligible while custom movement modifiers still fail closed.
- Added regression coverage for the player-specific baseline, the incorrect global baseline, legitimate vanilla sprinting, and malformed/custom modifiers.
- Build 010 remains the GUI compatibility release. Real-player Paper 26.2 testing exposed this admission issue before event approval, so build 011 supersedes it for staging.

## [2.4.1-010] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.1-010-j25-26.2.jar`

### Legacy translation compatibility

- Fixed startup validation for existing `translations.yml` files that predate `mainGui.titleColor`. The missing key now inherits bundled `#111827` in memory without rewriting the live file; an explicitly invalid color remains a fail-closed validation error.
- Added a regression test for Bukkit's explicit-versus-default configuration semantics.
- Build 009 was rejected by the persistent full-stack smoke when this compatibility gap was found. It was never tagged as a release candidate and is superseded by build 010.

## [2.4.1-009] — 2026-07-20

Expected artifact: `1MB-WalkThePlank-v2.4.1-009-j25-26.2.jar`

### Main menu

- Expanded the player menu from 27 to 54 slots and adopted the established 1MB CMI-API frame: light-blue stained-glass panes on the outer border only, with unused center slots left empty.
- Moved tutorial, play/queue, and statistics/leaderboard actions to centered slots 20, 22, and 24 without changing their behavior, permission checks, queue decisions, or owner/nonce/generation/inventory session protections.
- Replaced the hard-to-read legacy blue window title with configurable `#RRGGBB` coloring. The default `#111827` is a near-black charcoal; existing formatted title text is reduced to literal plain text before the trusted color is applied.
- Added an in-memory compatibility migration from the historical white pane to the light-blue 1MB pane. Existing live `translations.yml` and SQLite data do not need to be rewritten.
- Added regression tests for the exact 54-slot border geometry, deliberately empty center, action positions, title formatting removal, hex validation, and legacy pane migration.

## [2.4.0-008] — 2026-07-18

Expected artifact: `1MB-WalkThePlank-v2.4.0-008-j25-26.2.jar`

### Fair-play and categories

- Added configurable, separate Combo and Flawless categories without changing, multiplying, or migrating the historical Classic score.
- Combo records the longest target-to-target streak completed within the configured maximum gap. Flawless records the final Classic score only when every transition remained inside that gap.
- Added event-level and authoritative-sweep defenses for elytra/gliding, flight, riptide, ender-pearl and consumable teleports, projectile launch, vehicles/mounts, external velocity, movement effects, changed walk speed/attributes, and leaving the bounded arena.
- Added a conservative minimum inter-jump interval. A detected integrity failure ends the run with a zero persisted score and no category projection or reward; attempts are rate-limited in the audit and never automatically ban a player.
- `WalkRunEndEvent` now exposes the same authoritative zero score for a movement-disqualified run; the audit retains the observed and persisted values plus explicit scoring eligibility for investigation.

### Player experience

- Added configured native milestone feedback through Adventure action bars/titles plus Paper sounds and particles.
- Added UUID-owned SQLite preferences for full/reduced/off particles and independent sound/title toggles.
- Added `/walk settings`, category variants of `/walk stats` and `/walk top`, category/accessibility PlaceholderAPI values, and the separate `infinityparkour.preferences` player permission.

### SQLite and operations

- Added schema v3 tables for player preferences, category personal bests, and exact per-run category projections. Classic `scoreboard` rows and ranking semantics remain untouched.
- Added a bounded automatic migration-backup policy. Every matching automatic backup is verified with `PRAGMA quick_check` before the oldest excess files are deleted; the default retains five, the configurable range is 2–100, and operator-named files are ignored.
- Upgraded the bounded JSONL audit stream to a restart-verified SHA-256 hash chain with durable state and retention anchors. Existing unchained logs are preserved as explicitly named legacy archives; truncation or retained-record modification fails startup rather than silently resetting evidence. Archive pruning uses a recoverable staged rename/anchor/delete protocol so interruption immediately before or after the anchor commit is resolved deterministically at restart.
- `/walk admin doctor` now reports the configured/retained/pruned migration-backup state and privacy-safe audit-chain health.

### Platform and verification

- Updated the exact compile API to Paper `26.2.build.61-beta`, retaining Java 25, SQLite-only shading, PlaceholderAPI as the sole optional Java integration, and strict `-Xlint:all -Werror`.
- Added schema/category/preference/retention, simultaneous preference-field update, audit restart/tamper/truncation/checkpoint-deletion/legacy/prune-crash, listener-contract, and accessibility-unit coverage. Exact-artifact Paper smoke, live-copy schema-v3 preservation, adversarial real-client movement, and human event acceptance remain release gates.

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
- Build 007 subsequently passed its controlled scenarios, reproducibility/freeze, exact-artifact standalone and full-stack Paper smoke, and test-server synchronization, and was frozen as `v2.3.0-007-rc.1`. Human beta acceptance remained a separate event gate.

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
