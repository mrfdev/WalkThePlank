# WalkThePlank future improvements and release status

This is the authoritative future-development TODO and release-status document. It separates what is implemented in source release **v2.4.0 build 008** from what still needs server acceptance or future design work. “Implemented” means the behavior is present in source; the table or checklist identifies whether automated or Paper/live-data evidence still remains. It does not mean the summer-event candidate is production-approved. The release candidate still has to pass [checklist-walktheplank.md](checklist-walktheplank.md).

Status labels:

- **Implemented** — present in build-008 source.
- **Implemented; beta verification pending** — present, but the final JAR still needs the named Paper/live-data test.
- **Partial** — a safe foundation exists, but an important workflow or assurance remains.
- **Proposed** — not present and must not be advertised as a current feature.

## Deferred modernization queue

Build 003 remains the historical modernization baseline, build 004 the event-safety release, build 005 the destructive-testing foundation, build 006 the off-main durability release, and build 007 the command/API modernization release. Build 008 adds bounded migration-backup retention, milestones/accessibility, separate Combo/Flawless categories, rate-limited movement anomaly evidence, and a verified audit chain. Every remaining unchecked feature stays deferred so its evidence cannot be confused with this candidate.

1. [x] **v2.1.1 build 004 — event-safety and operations implemented; beta acceptance still pending.** External-teleport decisions occur at `HIGHEST` with observation-only `MONITOR` and next-tick verification; GUI sessions bind owner UUID/nonce/generation/exact inventory with one pending action; trusted formatting is parsed separately from literal dynamic components with explicit `minimessage:` opt-in and legacy `&` compatibility; `/walk admin doctor` produces a privacy-safe report with an asynchronous SQLite probe.
2. [x] **v2.1.2 build 005 — disposable Paper integration and fault-injection harness implemented; final-candidate and real-client evidence pending.** A separate test plugin, Java 25 Class-File API instrumented-copy builder, 24 named failpoints, two-start/PlaceholderAPI/lifecycle/log runner, real-player queue/teleport/GUI/reconnect/exit probes, reflection event contracts, and positive/negative production-isolation checks are present. Development-artifact runs passed the automated two-start profile and the `config.after_runtime_commit` exit-97/recovery profile; repeat them against the clean committed candidate and complete every applicable real-client/hard-kill checklist row before treating this item as release-qualified.
3. [x] **v2.2.0 build 006 — two-phase off-main durability pipeline implemented; Paper/destructive acceptance pending.** Bukkit/Paper capture, validation, restoration, and mutation remain on the primary thread. Immutable journal bytes go to a bounded FIFO recovery writer; audit/export/config work uses an independent bounded operations writer. Exclusive lifetime ownership is acquired before recovery journals open and remains held until any delayed writer truly terminates. Successful completion returns to the primary thread and revalidates exact run/session/platform generations, arena/block leases, player state, permissions, and block fingerprints. Uncertain post-rename commits and failed discards retain exact evidence for retry. Successor preparation begins while the player traverses the current jump and pre-placement occurs when ready; an early landing waits/rechecks without async world mutation. Predecessor journal deletion/fsync completes off-thread. Reload, administrative recovery, and shutdown use explicit barriers/completion queues without async Bukkit access or caller-thread durability fallback.
4. [ ] **Remaining Summer Season feature bundle.** Preserve the imported Classic leaderboard while adding cosmetic themes, a versioned daily course and separate ranking, seasonal Momentum, quests, durable UUID claim keys, and a community plank goal. Milestones, accessibility preferences, and separate Combo/Flawless categories moved into build 008; daily/seasonal scoring must still run in shadow mode before rewards or public ranking.
5. [x] **v2.3.0 build 007 — command/API modernization and machine qualification completed; human acceptance remained pending.** The former 2,789-line handler is split into player, queue, arena, season/export, reward, investigation, and database/diagnostic modules. A typed Brigadier tree is registered from the standard `JavaPlugin` through `LifecycleEvents.COMMANDS`, retaining aliases and compatibility forms without requiring `paper-plugin.yml`. Online-player and UUID arguments, bounded limits, enum/literal branches, dynamic suggestions, and destructive confirmation words are represented in the tree. Experimental Dialog/data-component APIs remain intentionally absent. SQLite-only storage, no direct CMI database access, no Vault coupling, no uncertain-reward replay, and the historical Classic leaderboard are unchanged. The controlled scenarios, reproducibility/freeze, standalone/full-stack Paper smoke, and test-server synchronization passed for the tagged `v2.3.0-007-rc.1` artifact; that evidence is historical and does not qualify build 008.
6. [x] **v2.4.0 build 008 — fair-play, accessibility, categories, retention, and audit-chain source implemented; Paper/human acceptance pending.** Schema v3 adds UUID-owned preferences and atomic per-run/category projections without altering Classic. Configured native milestones respect full/reduced/off particles and sound/title toggles. Combo and Flawless are separate leaderboards. Event defenses plus an authoritative sweep cover flight/glide/riptide/pearls/consumable teleports/projectiles/vehicles/velocity/effects/attributes/walk speed/bounds/jump rate; detected integrity failures persist no score and never auto-ban. Automatic backups are quick-checked before bounded pruning, and retained JSONL audit records are hash-chained and restart-verified.

## Product and data guardrails

Future work must preserve these rules:

- UUIDs, never usernames, own progress. A last-known name is display metadata only.
- The existing 100-row all-time leaderboard and top ten survive upgrades unless an operator deliberately restores a backup.
- Never guess or merge identity from CMI, another plugin, or an online profile API.
- SQLite remains the sole backend unless a real future requirement justifies a separately designed migration. Do not re-add unused MySQL/MariaDB code or drivers.
- Never silently replace, relocate, or open a second empty database when the configured file is unsafe or unavailable.
- Keep the Bukkit name `InfinityParkour` and `plugins/InfinityParkour/` data path compatible with the live installation.
- Journal before world mutation, restore player/world state on every controlled exit, and quarantine ambiguity instead of overwriting a third-party block.
- Never automatically replay an externally dispatched reward whose outcome is uncertain.
- Never persist raw reward command arguments merely to make replay convenient.
- PlaceholderAPI remains the only optional Java integration. CMI, UltimateFireworks, and PyroWelcomesPro are targeted soft-dependency startup-order hints for configured command-root validation, not Java/API integrations; CMILib, Vault, and PyroLib remain outside WalkThePlank's dependency graph.
- Paper and PlaceholderAPI classes remain provided; SQLite JDBC remains the only shaded runtime library.
- Every public behavior change gets a version/build increment, immutable 1MB artifact name, documentation, tests, staging rehearsal, and rollback plan.

## Implemented in v2.4.0-008

Build 008 includes all previous modernization layers documented in [CHANGELOG.md](CHANGELOG.md), plus the fair-play, category, accessibility, backup-retention, and audit-chain work above.

### Build, platform, and packaging

| Capability | Status | Notes |
| --- | --- | --- |
| Gradle build and wrapper | **Implemented** | Gradle 9.6.1, Shadow 9.5.1, strict Java 25 compilation. |
| Paper target | **Implemented; final build-008 smoke pending** | Exact compile API is 26.2 build 61 beta. Repeat the controlled profile and full candidate smoke against build 61 or newer before approval. |
| Standalone SQLite JAR | **Implemented** | SQLite JDBC 3.53.2.0 is shaded; MySQL/MariaDB is absent. |
| Archive-composition gate | **Implemented** | Rejects bundled Paper/Bukkit/PlaceholderAPI/live-database classes or files and remote-database drivers. |
| Isolated scenario artifacts | **Implemented; final-candidate rerun pending** | The harness is an independent Paper plugin and the Java 25 Class-File API transformer writes a distinct `TEST-ONLY-` copy. Neither is packaged beneath `build/libs/` or allowed to replace the production JAR. Development-artifact isolation and controlled-profile gates passed. |
| Production-isolation proof | **Implemented; final build evidence pending** | Negative scans require no scenario classes, controls, canaries, properties, manifest markers, commands, or 24 failpoint IDs in production; positive controls require their presence in test artifacts so a broken scan cannot pass silently. |
| Controlled Paper 26.2 profile | **Implemented; clean-candidate rerun pending** | The development-artifact two-start run passed restart-marker, PlaceholderAPI absent/present, config reload, terminal disable/service removal, fresh-JVM enable, deterministic marker, broad log, port-release, and SQLite quick-check assertions. The automated `config.after_runtime_commit` hard halt also exited 97 and recovered cleanly. Repeat both against the clean committed candidate; real-client and the remaining named hard-kill cases stay separate acceptance work. |
| Listener annotation contracts | **Implemented** | Reflection tests lock gameplay/menu `EventHandler` priorities and `ignoreCancelled` values, including `HIGHEST` decisions and observation-only `MONITOR` callbacks. |
| 1MB naming/build metadata | **Implemented; clean freeze pending** | The exact 1MB filename plus Git commit/dirty provenance are embedded in the resource and manifest. Final size/hash belong to the annotated candidate tag and operator release archive so recording them cannot change the embedded source commit. |
| Dependency cleanup | **Implemented; final/provider matrix pending** | PlaceholderAPI 2.12.3 is the only Java integration. CMI, UltimateFireworks, and PyroWelcomesPro are soft startup-order hints for configured roots; CMILib, Vault, and PyroLib are not dependencies. Repeat PlaceholderAPI absent/present and external reward content/provider checks against build 008. |

### Persistence, identity, seasons, and history

| Capability | Status | Notes |
| --- | --- | --- |
| SQLite schema v3 | **Implemented; live-copy repeat pending** | Existing Classic scores plus seasons/run/reward durability, UUID-owned preferences, separate category bests, and exact per-run category projections. |
| Verified migration backup and retention | **Implemented; live-copy repeat pending** | `VACUUM INTO` plus `PRAGMA quick_check`; exact automatic candidates are all verified before the oldest excess is pruned. Default 5, configurable 2–100, operator-named files untouched. |
| UUID ownership | **Implemented** | Existing UUID rows are preserved; unresolved legacy rows remain visible but cannot be claimed by name. |
| Schema drift rejection | **Implemented** | Required constraints, foreign keys, and named index definitions are checked fail-closed. |
| Exclusive SQLite instance ownership | **Implemented; process rehearsal pending** | A safe sibling OS file lock prevents a second repository from opening the same database; it releases after a clean drain or process exit, and an unlocked lock file may remain. |
| Event seasons | **Implemented** | Planned/active/closed/archived lifecycle, one active season, monotonic transitions, start-time season capture. |
| Durable run history | **Implemented** | STARTED is committed before gameplay mutation; completion and score projection are idempotent. |
| Atomic completion/reward intent | **Implemented** | Completion, positive-score projection, and eligible redacted plan/steps commit together; conflict rolls back everything, exact retry does not redispatch, and retroactive plan attachment is rejected. |
| Bounded retention | **Implemented** | Newest 10,000 prunable `COMPLETED`/`ABORTED` runs, plus all active `STARTED`, unresolved `UNKNOWN`, and uncertain-ledger evidence. |
| Terminal reward tombstones | **Implemented** | Compact plan/run/idempotency evidence prevents replay after an old full ledger is pruned. |
| UUID-only export | **Implemented; beta verification pending** | Atomic CSV/JSON for all-time, current season, or historical season; unresolved identity fails the whole pair. |

### Gameplay, world safety, and recovery

| Capability | Status | Notes |
| --- | --- | --- |
| Modern endless course generation | **Implemented; gameplay verification pending** | Bounded jump planner, reachability envelope, headroom checks, intact-target landing validation. |
| Multiple arenas and policies | **Implemented** | RANDOM, ROUND_ROBIN, LEAST_RECENTLY_USED, and PINNED with safe fallback. |
| Player-state restoration | **Implemented; hard-kill rehearsal pending** | A privacy-bounded atomic journal captures UUID/run/arena-owned return and state before mutation. Controlled exits and verified reconnects restore it; velocity and fall distance are normalized to zero, not captured/restored. |
| World protection | **Implemented; abuse matrix pending** | Blocks common interaction, fluids, fire, explosion, piston, and entity mutation across protected bounds. |
| Active-run isolation | **Implemented; abuse matrix pending** | Incoming/outgoing damage, movement advantages, item pickup/drop, inventory click/drag, hand swaps, held-slot changes, and entity interactions are denied during a run; unresolved reconnect evidence quarantines the affected player during verification. |
| Persistent restoration journal | **Implemented; tile/PDC crash test pending** | Primary-thread exact structure capture, bounded FIFO off-thread hash/write/fsync/atomic move, primary-thread fingerprint revalidation/mutation, and idempotent recovery. |
| Successor durability pipeline | **Implemented; latency/gameplay verification pending** | Durable preparation begins during traversal and the next platform is pre-placed when ready; an early landing waits/rechecks without async mutation. Predecessor world restoration stays primary-thread while journal deletion and exact lease release complete off-thread. |
| Recovery ownership leases | **Implemented** | Arena ownership binds run UUID/session generation; block ownership additionally binds platform generation, preventing late callbacks from releasing newer work. |
| Conflict quarantine | **Implemented** | Missing worlds and third-party block changes remain pending/quarantined instead of being overwritten. |
| Journal hard limits | **Implemented** | 1,024 records, 16 MiB original snapshot, 24 MiB record, 256 MiB aggregate. |
| Stale temporary handling | **Implemented** | Recognizable regular pre-commit temp files are removed; unknown/non-regular temp entries fail startup. |
| Platform material policy | **Implemented; live server verification pending** | Existing physical checks plus centralized non-deprecated exact/suffix denial for stateful/workstation families, including copper chests/shelves; `STONE` and live `JACK_O_LANTERN` remain eligible. |
| Safe return fallback/quarantine | **Implemented; teleport-failure rehearsal pending** | Every custom/captured/world-spawn candidate is live-validated for physical safety and exclusion from all arena volumes. Unresolved return evidence is durable across a hard kill and is cleared only after verified state plus teleport recovery. |
| External teleport ordering | **Implemented; Paper listener-order rehearsal pending** | Active-run decisions occur at `HIGHEST`; `MONITOR` only records the final cancelled/destination state, and cleanup commits next tick only when the player, run, attempt, world, and destination still match. |
| Movement-integrity controls | **Implemented; adversarial client matrix pending** | Flight/glide/riptide, exploit teleports, projectiles, vehicles, velocity, effects, speed state, arena bounds, and minimum inter-jump timing are blocked/rechecked. A detected altered run persists zero score and no category/reward; anomaly evidence is rate-limited and never auto-bans. |
| Combo and Flawless | **Implemented; gameplay calibration pending** | Separate atomic personal-best categories. Combo is the maximum within-gap streak; Flawless is the final Classic score only if every transition met the target. Classic is unchanged. |
| Milestones/accessibility | **Implemented; client presentation pending** | Configured native action bar/title/sound/particle feedback with UUID-owned full/reduced/off particles and sound/title toggles. |

### Capacity, commands, and operator tooling

| Capability | Status | Notes |
| --- | --- | --- |
| FIFO queue/readiness | **Implemented; multiplayer rehearsal pending** | Join/leave/status/ready, cooldown, expiry/reminders, action-bar state, pause with frozen positions/deadlines, resume, and drain. |
| Guarded arena editor | **Implemented; rollback rehearsal pending** | Worker capture/persistence, primary-thread Paper validation, generation CAS, exact token-bound backup, final disk verification, pending-work idle gate, and shutdown reconciliation. |
| Season administration | **Implemented** | Create/list/activate/close/reopen/archive with separate permission. |
| Export administration | **Implemented** | Separate permission and audited successful export. |
| Reward investigation | **Implemented; crash rehearsal pending** | Status list, redacted inspect, explicit UNKNOWN resolution, and inspected-PENDING reconciliation by confirmed abandon with audit. |
| Retained-run investigation | **Implemented; role/server rehearsal pending** | Separate permission; exact status/player UUID/run UUID/arena/season filters; prepared repository filters for release and a maximum 366-day start window; redacted newest-first 1–100-row output with linked plan status; successful-query audit. Names are not ownership keys. |
| Reward completion barrier | **Implemented; delayed-provider rehearsal pending** | A player cannot start another run while an eligible durable reward plan is being dispatched or finalized. |
| Reward command-root allow-list | **Implemented; event-policy review pending** | Effective 1–32 canonical roots, configuration-time membership validation, and runtime rejection before command lookup/dispatch. An omitted key inherits bundled `say`, `cmi`, `uf`, and `welcomes`; unused providers should be removed for the event. |
| Validate-only workflow | **Implemented** | Safe fingerprint, aggregate errors/warnings, strict v2 keys, legacy missing-version warnings. |
| Nested shape and reward-size validation | **Implemented** | Explicit v2 rejects unknown arena/exit/tier keys; legacy reports them; an inclusive overlap sweep rejects any score that could exceed 100 durable reward steps. |
| Permission separation | **Implemented; role matrix pending** | Player preferences plus separate arena/queue/season/export/reward/run-investigation/recover/validate/debug/open/stop duties. |
| Safe health/debug pages | **Implemented; log review pending** | Bounded database, queue, arena, season, restoration, reward, hook, and runtime summaries. |
| Privacy-safe doctor report | **Implemented; server/privacy verification pending** | `/walk admin doctor` adds audit-chain state and configured/retained/pruned backup counts to source/runtime/hook/worker/recovery/reward health, with SQLite `quick_check`, sizes, and latency off-thread. Sensitive record content remains excluded. |

### GUI, placeholders, API, and operations

| Capability | Status | Notes |
| --- | --- | --- |
| Nonce/generation GUI sessions | **Implemented; exploit matrix pending** | Owner UUID, random nonce, monotonic generation, exact inventory identity, stale-session invalidation, duplicate-click suppression, one pending action, and immediate permission/state revalidation supplement typed-holder click/drag cancellation and the 750 ms open limiter. |
| Literal dynamic components and MiniMessage opt-in | **Implemented; live translation verification pending** | Trusted templates are parsed before dynamic values are inserted literally. Unmarked values retain legacy `&` compatibility; only an explicit `minimessage:` marker opts an operator-controlled template into MiniMessage, and bundled GUI titles begin the gradual migration. |
| PlaceholderAPI expansion | **Implemented; server verification pending** | All-time, active season, top ten, capacity, queue, and active-run values; game state is served from immutable async-safe publications. |
| Read-only Bukkit API | **Implemented** | Release, UUID player/all-time leaderboard, active run, capacity, active season, per-player queue, and bounded recent-run views on the primary thread. |
| Lifecycle events | **Implemented** | Cancellable run start/reward-plan plus jump, run end, and personal-best events. |
| Structured audit and tamper evidence | **Implemented; Paper hard-kill review pending** | Every retained JSONL record is SHA-256 chained with a durable state and retention anchor, verified at startup. Legacy logs are explicitly preserved, tampering/truncation fails startup, and rate-limited security anomalies are included without sensitive values. Prune interruption immediately before and after the anchor commit is unit-locked for deterministic recovery; repeat both kill points on Paper. |
| Release health policy | **Partial** | Metrics and bounded shutdown exist; server-level alerting/runbook ownership remains operational work. |

## Known limitations and incomplete work

These are not hidden defects; they are explicit design or release boundaries that must be accepted or fixed.

### P0 — finish before event approval

1. **Qualify and commit the build-008 candidate.** Commit the reviewed source, create two byte-identical clean builds, rerun the controlled two-start and automated hard-kill profiles against that exact artifact, then record archive verification, final hash/size/test result, Paper 26.2 build-61-or-newer smoke, test-server sync, and plugin-log review. Earlier evidence remains historical.
2. **Repeat schema-v3 migration on a disposable copy of all 100 live rows.** Compare every Classic UUID/score/top-ten position, verify source/database/automatic backups, exercise retention without touching operator files, and prove `_resources` stayed unchanged.
3. **Complete the real-client abuse/recovery matrix.** The test-only plugin records deterministic evidence and its player-assisted stale command exercises the real scheduled callback/revalidation path without fabricating packets. A real player must still perform simultaneous queue/start actions, GUI click/drag/hotbar/double-click/creative cases, every cleanup cause, cancelled/retargeted teleports, reconnect recovery, world protection, signs, containers, tile PDC, conflicts, and missing-world recovery. Run named hard-kill/restart cases separately.
4. **Rehearse reward uncertainty with staging accounts.** Test PENDING discovery/abandon and UNKNOWN downstream investigation/resolution. Assign a named operator and never interpret a resolution as a replay request.
5. **Rehearse rollback.** Restore the prior JAR and matching data folder together, measure recovery time, and document that database rollback discards later scores.
6. **Resolve distribution rights.** No approved license file is present. Keep the build private/custom unless relevant rights holders authorize redistribution.

### P1 — durability and operator workflow

#### Extend staff run/reward investigation

**Core retained-run queries implemented; export/annotation workflow proposed.** Build 003 introduced a separate investigation permission and redacted commands for recent runs by status, player UUID, exact run UUID, arena, or season, plus one-run inspection with linked reward-plan status. The repository additionally supports exact release and a complete start-time interval capped at 366 days. All results are newest-first and capped at 100, and every successful query audits the operator UUID, safe filter, and count.

Remaining design work:

- export of a bounded investigation bundle without names, raw commands, or absolute paths;
- explicit annotations/operator decisions with immutable audit events;
- an approved in-game syntax or public-API contract for the already bounded release/time filters;
- query rate limiting if production history/role usage makes it necessary.

Do not add username ownership lookup or a “rerun reward” button.

#### Complete administrative audit coverage

**Implemented; server log review pending.** Admin stop/open/reload/validate now emit safe result events with player operator UUID when applicable and an explicit `player`/`system` actor category. Existing arena edits, restoration recovery, queue controls, season transitions, exports, and reward/run investigations use the same actor convention. Full coordinates, raw configuration, structures, and command bodies remain excluded.

#### SQLite maintenance and shutdown runbook

- **Build-004 doctor probe implemented; beta verification pending.** `/walk admin doctor` schedules read-only `quick_check` plus database/WAL size, migration-backup count, and latency reporting on the repository worker, while combining it with privacy-safe runtime/journal/reward/queue/task health.
- **Implemented in build 008; live-copy verification pending.** Exact automatic backups are all quick-checked, newest configured count is retained, and operator-named backups are untouched.
- Document that queued operations settle on close timeout, while an already-running SQLite JDBC call may outlive an unsuccessful close if interruption is ignored.
- Add disk-space preflight and clearer full/read-only filesystem categories without exposing absolute paths to players.

#### Persist minimal crash-recovery player return evidence

**Implemented; remaining named destructive rehearsal pending.** Player/run/arena-owned state is fsynced before plugin mutation or arena teleport. It stores no inventory or names, quarantines an affected online player while exact retained-run ownership is verified, applies terminal death/teleport semantics without replaying superseded state, and deletes only the exact record after applicable cleanup succeeds. Malformed, orphaned, or ambiguous evidence is retained and fails closed; an exact valid copied record is intentionally treated as indistinguishable operator-owned evidence. Build 005 supplies named test-only journal, block, and teleport failpoints plus restart evidence. The automated runtime-commit hard halt/recovery passed on development artifacts; the player-assisted hard-process-kill/reconnect matrix still has to be run and reviewed.

Valid pending evidence withholds its exact arena across restarts; unreadable evidence with unknown arena ownership withholds all arenas. `/walk admin recover` reissues ownership lookups for online affected players. There is intentionally no online discard button: unverifiable evidence uses the documented stopped-server, backup-first matching-data restore/manual-resolution procedure and is preserved outside the active journal rather than deleted.

#### Close equipment and attribute movement-fairness gaps

**Implemented; custom-item beta matrix pending.** Build 003 introduced rejection of a changed movement-speed base and every modifier except the exact vanilla sprint modifier at admission, immediately before activation, and during the one-second active sweep. A newly ineligible run ends without rewards, and held-slot changes are denied while active. WalkThePlank does not delete, rewrite, or serialize equipment. Test ordinary sprinting plus standard/custom Paper/PDC equipment and external attribute providers before event approval.

#### Explicit offline UUID import tool

The runtime correctly refuses username matching. If unresolved historical rows ever exist, build a separate stopped-server tool that is:

- UUID-only and schema-validated;
- backup-first and dry-run by default;
- supplied with an explicit operator mapping file;
- collision-detecting, fully auditable, and reversible;
- incapable of querying CMI or an online service implicitly.

#### Configuration upgrade assistance

Keep strict `configVersion: 2`, but add a stopped-server or validate-only diff that shows missing current keys, obsolete keys, and safe suggested values. Never rewrite a live configuration automatically or expose reward command bodies in the diff.

## Feature roadmap after the event

### P2 — gameplay variety without changing classic scores

#### Difficulty profiles

Add Classic, Relaxed, and Expert profiles with separate reachability envelopes, timeouts, palettes, and leaderboards. Classic must retain the current score meaning and imported all-time table.

#### Configured themes

Support pirate/summer, tropical, nether, end, ice, or rainbow presentation through validated material/particle/sound sets. Theme alone must not change scoring; a mechanically different theme becomes a separate category.

#### Seeded daily challenge

Persist date, seed, timezone policy, and algorithm version so every player receives the same deterministic course across restarts. Keep the daily leaderboard separate and do not use the public seed for security-sensitive randomness.

#### Milestones, accessibility, Combo, and Flawless

**Implemented in build 008; Paper/client calibration pending.** Native configured milestone feedback respects UUID-owned particle/sound/title preferences. Combo and Flawless use separate atomic score tables and commands/placeholders; no multiplier or Classic rewrite exists. Future work here is presentation polish and tuning only, not a new scoring migration.

### P2 — presentation and social play

- Paged all-time/season/category leaderboards with explicit selector labels.
- Queue/capacity item, current score/idle/elapsed state, best delta, and next-rank target in the GUI.
- Staff-only health/quarantine/reward-uncertainty views with confirmation screens.
- Cosmetic personal ghost/trail using bounded target-to-target samples, not full movement packets.
- Spectator mode that consumes no arena, cannot affect collision, and respects vanished staff.
- Team relay as a separate score model with disconnect, anti-boosting, and leaderboard rules.

### P2 — integrations

- Optional WorldGuard-style region validation while retaining native protection as the baseline.
- A versioned public API extension for historical-season leaderboards and filtered/paginated run investigation using immutable DTOs.
- Additional cancellable events only where cancellation semantics and durable state order are explicit.
- A read-only metrics/export endpoint or file for dashboards; never let a web application connect directly to the live SQLite file.
- Downstream reward idempotency tokens only for providers that explicitly support them; keep generic console dispatch conservative.

## Security and abuse hardening backlog

- Keep the implemented reward command-root allow-list minimal for each event and test both configuration-time and runtime enforcement whenever provider commands change.
- Add optional once-per-player/season/tier policies backed by durable UUID keys.
- Rate-limit expensive staff read/export operations without preventing emergency leave/recovery.
- **Implemented in build 008:** rate-limited movement anomaly evidence with no automatic ban.
- **Implemented in build 008:** restart-verified SHA-256 audit segments and durable retention anchors. A future signature/external immutable anchor would strengthen evidence against a filesystem-authorized operator.
- Add privacy retention policy for run history/audit exports and a documented operator deletion workflow that preserves referential integrity.
- Reuse build 004's owner/nonce/generation/exact-inventory session identity, duplicate suppression, one-pending-action gate, and immediate revalidation for every future GUI action; destructive actions additionally need explicit confirmation and a purpose-specific nonce. Never authorize from title, lore, material, or slot alone.
- Continue rejecting unsafe paths, symlinks, control characters, oversized values, nested audit data, and malformed identifiers.

## Testing investments

### Reproducible Paper integration harness

**Implemented; final-candidate rerun and real-client completion pending.** The separate `WalkThePlank-ScenarioHarness` plugin and disposable Paper 26.2 runner cover two starts, restart evidence, PlaceholderAPI absent/present, `/walk admin reload`, terminal disable/service removal, fresh-JVM enable, API/lifecycle checks, deterministic scenario markers, and automated log assertions. The development-artifact two-start run passed all of those automated checks. It does not attempt an unsupported same-instance re-enable after Paper unregisters the disabled plugin's configured classloader. The harness also observes real-player queue contention, run exits, teleports, reconnects, and GUI gestures, but deliberately reports those as `PENDING` until an actual client performs them. It must operate only on generated/disposable data and never point at `_resources` directly.

The harness is not production code. It fails closed without an explicit disposable-profile flag, nonce-bound marker, exact real working-directory/generated-layout/data-path contract, and the test bridge loaded from the instrumented target's classloader. The property-armed failpoint bridge enforces the same root/nonce/marker checks before it can halt at a selected startup or runtime boundary. The runner also rejects symlinked destructive roots. Its `TEST-ONLY-` artifacts live under `build/scenario-artifacts/`; the deployable JAR remains under `build/libs/`.

### Fault injection

**Implemented for 24 reviewed boundaries; final rerun and remaining hard-kill matrix pending.** Java 25's Class-File API transforms a copy of the deployable JAR and injects `HALT` controls at every point. The development-artifact `config.after_runtime_commit` profile emitted exact `ARMED`/`REACHED` markers, halted with exit 97, released its port, and recovered on the same disposable profile with SQLite `quick_check=ok`. Seventeen reversible/contained boundaries additionally support `THROW` or releasable `BLOCK`; the player/restoration journal rename and delete points plus configuration candidate rename, disk commit, and runtime commit are `HALT`-only:

- both player and block-restoration journal temp writes, file fsyncs, atomic renames, directory fsyncs, and deletes;
- block placement/restoration and start/return teleports;
- score completion plus reward claim, external dispatch, and recorded outcome;
- CSV and JSON export renames;
- configuration backup/candidate renames and disk/runtime commit points.

`verifyReleaseJar` and `verifyProductionScenarioIsolation` prove that the production artifact contains none of these controls while requiring positive detection in the two test artifacts. Remaining fault investments include audit append/rotation, scheduler rejection, retention/tombstone insertion, SQLite lock/disk-full/read-only behavior, timeout, and interrupted close.

Every accepted future must settle, every ambiguous external side effect must remain visible, and no fault may trigger blind replay. `HALT` proves process-interruption handling only; it does not emulate power loss below the operating-system/filesystem boundary. Record actual SQLite `journal_mode`, `synchronous`, and `quick_check` values after each restart. Also preserve and classify known partial-output behavior: CSV/JSON files are each atomically renamed but not committed as one pair, and interrupted export/config temporary files are not automatically cleaned today.

### Load and soak tests

Exercise maximum configured arenas, queue churn, GUI spam, placeholder reads, leaderboard reads, score writes, audit rotation, and journal cleanup for several hours. Record main-thread time, database latency, memory, pending mutations, queue fairness, retained-run growth, and quarantine count.

### Upgrade and disaster-recovery matrix

Test fresh install, the supplied legacy data, already-migrated schema v2/v3, missing-version config, invalid/newer schema, older candidate rollback, process kill at each durable boundary, missing world, corrupt journal/audit chain, and restored backup. Verify exact Classic row/UUID/top-ten preservation throughout.

## Deliberate non-goals

Unless a future requirement changes with a documented design review, do not add:

- MySQL/MariaDB support or a remote database driver;
- username-based ownership, automatic CMI database import, or online UUID lookup;
- automatic replay of `PENDING`, `DISPATCHING`, or `UNKNOWN` reward work;
- raw reward commands in SQLite, exports, audit, health, or public API;
- direct live-database access for websites;
- mutable session/database handles in the Bukkit API;
- bundled Paper, Bukkit, PlaceholderAPI, CMI, or CMILib classes;
- compatibility shims for unsupported 1.20.x, 1.21.x, or 26.1.x servers in the current Paper 26.2 artifact.

## Definition of done for any future item

A roadmap item is complete only when all applicable evidence exists:

1. behavior, failure policy, permissions, privacy impact, and migration are documented;
2. automated success/failure/abuse coverage passes with strict compilation;
3. archive composition and standalone startup still pass;
4. a production-like Paper server test covers real scheduler/world/plugin behavior;
5. live-data invariants and rollback are proven on a disposable copy;
6. commands, permissions, placeholders, API, changelog, README, and beta checklist agree;
7. the build number is incremented and the exact final artifact is checksummed;
8. an operator explicitly signs off—source completion alone is never production approval.
