# WalkThePlank future improvements and release status

This is the authoritative future-development TODO and release-status document. It separates what is implemented in source release **v2.1.0 build 003** from what still needs server acceptance or future design work. “Implemented” means the behavior is present in source; the table or checklist identifies whether automated or Paper/live-data evidence still remains. It does not mean the summer-event candidate is production-approved. The release candidate still has to pass [checklist-walktheplank.md](checklist-walktheplank.md).

Status labels:

- **Implemented** — present in build-003 source.
- **Implemented; beta verification pending** — present, but the final JAR still needs the named Paper/live-data test.
- **Partial** — a safe foundation exists, but an important workflow or assurance remains.
- **Proposed** — not present and must not be advertised as a current feature.

## Deferred modernization queue

These items are explicitly excluded from v2.1.0 build 003. Complete them in separate, incremented builds after build 003 is frozen and accepted so their evidence cannot be confused with the event candidate.

1. [ ] **v2.1.1 build 004 — event-safety and operations.** Correct external-teleport event priority, add owner UUID/nonce/generation GUI sessions, render dynamic text as literal Adventure components, and add a privacy-safe `/walk admin doctor` report.
2. [ ] **Disposable Paper integration and fault-injection harness.** Reuse the controlled-scenario approach proven in other 1MB projects for two-start recovery, queue contention, event ordering, GUI abuse, PlaceholderAPI presence/absence, log assertions, and named failures at every durable boundary. Test-only hooks and harness classes must be absent from the production JAR.
3. [ ] **Two-phase off-main durability pipeline.** Keep Bukkit/Paper capture, validation, and mutation on the primary thread; move journal/audit/config filesystem work to bounded plugin-owned workers; return to the primary thread and revalidate the run generation and authoritative state before mutation. Preserve append-before-mutation, quarantine, and crash-recovery guarantees.
4. [ ] **Summer Season feature bundle.** Preserve the imported Classic leaderboard while adding cosmetic themes, milestones, accessibility preferences, a versioned daily course and separate ranking, seasonal Momentum, quests, durable UUID claim keys, and a community plank goal. Run new scoring in shadow mode before rewards or public ranking.
5. [ ] **Post-event command/API modernization.** Split the command, game, configuration, and repository monoliths by domain; migrate supported commands to Paper's lifecycle-registered Brigadier tree; evaluate experimental Dialog/data-component APIs only behind isolated version-gated adapters when they provide concrete value.

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

## Implemented in v2.1.0-003

### Build, platform, and packaging

| Capability | Status | Notes |
| --- | --- | --- |
| Gradle build and wrapper | **Implemented** | Gradle 9.6.1, Shadow 9.5.1, strict Java 25 compilation. |
| Paper target | **Implemented; console smoke passed** | Paper API/runtime 26.2 build 60 beta passed full-stack and standalone startup/reload/shutdown; human in-game acceptance remains mandatory. |
| Standalone SQLite JAR | **Implemented** | SQLite JDBC 3.53.2.0 is shaded; MySQL/MariaDB is absent. |
| Archive-composition gate | **Implemented** | Rejects bundled Paper/Bukkit/PlaceholderAPI/live-database classes or files and remote-database drivers. |
| 1MB naming/build metadata | **Implemented; clean freeze pending** | The exact 1MB filename plus Git commit/dirty provenance are embedded in the resource and manifest. Final size/hash belong to the annotated candidate tag and operator release archive so recording them cannot change the embedded source commit. |
| Dependency cleanup | **Implemented; provider content matrix pending** | PlaceholderAPI 2.12.3 is the only Java integration. CMI, UltimateFireworks, and PyroWelcomesPro are soft startup-order hints for configured roots; CMILib, Vault, and PyroLib are not dependencies. Full-stack and no-optional-plugin cold starts pass; external reward content still needs in-game rehearsal. |

### Persistence, identity, seasons, and history

| Capability | Status | Notes |
| --- | --- | --- |
| SQLite schema v2 | **Implemented** | Scores, seasons, season scores, run history, reward plans/steps, and reward tombstones. |
| Verified migration backup | **Implemented; live-copy repeat pending** | `VACUUM INTO` sibling backup plus `PRAGMA quick_check` before transactional migration. |
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
| Persistent restoration journal | **Implemented; tile/PDC crash test pending** | Synchronous pre-mutation record, exact structure snapshot, hashes, atomic move, idempotent recovery. |
| Conflict quarantine | **Implemented** | Missing worlds and third-party block changes remain pending/quarantined instead of being overwritten. |
| Journal hard limits | **Implemented** | 1,024 records, 16 MiB original snapshot, 24 MiB record, 256 MiB aggregate. |
| Stale temporary handling | **Implemented** | Recognizable regular pre-commit temp files are removed; unknown/non-regular temp entries fail startup. |
| Platform material policy | **Implemented; live server verification pending** | Existing physical checks plus centralized non-deprecated exact/suffix denial for stateful/workstation families, including copper chests/shelves; `STONE` and live `JACK_O_LANTERN` remain eligible. |
| Safe return fallback/quarantine | **Implemented; teleport-failure rehearsal pending** | Every custom/captured/world-spawn candidate is live-validated for physical safety and exclusion from all arena volumes. Unresolved return evidence is durable across a hard kill and is cleared only after verified state plus teleport recovery. |

### Capacity, commands, and operator tooling

| Capability | Status | Notes |
| --- | --- | --- |
| FIFO queue/readiness | **Implemented; multiplayer rehearsal pending** | Join/leave/status/ready, cooldown, expiry/reminders, action-bar state, pause with frozen positions/deadlines, resume, and drain. |
| Guarded arena editor | **Implemented; rollback rehearsal pending** | Atomic candidate write, validation, backup, compare-before-write, conditional rollback. |
| Season administration | **Implemented** | Create/list/activate/close/reopen/archive with separate permission. |
| Export administration | **Implemented** | Separate permission and audited successful export. |
| Reward investigation | **Implemented; crash rehearsal pending** | Status list, redacted inspect, explicit UNKNOWN resolution, and inspected-PENDING reconciliation by confirmed abandon with audit. |
| Retained-run investigation | **Implemented; role/server rehearsal pending** | Separate permission; exact status/player UUID/run UUID/arena/season filters; prepared repository filters for release and a maximum 366-day start window; redacted newest-first 1–100-row output with linked plan status; successful-query audit. Names are not ownership keys. |
| Reward completion barrier | **Implemented; delayed-provider rehearsal pending** | A player cannot start another run while an eligible durable reward plan is being dispatched or finalized. |
| Reward command-root allow-list | **Implemented; event-policy review pending** | Effective 1–32 canonical roots, configuration-time membership validation, and runtime rejection before command lookup/dispatch. An omitted key inherits bundled `say`, `cmi`, `uf`, and `welcomes`; unused providers should be removed for the event. |
| Validate-only workflow | **Implemented** | Safe fingerprint, aggregate errors/warnings, strict v2 keys, legacy missing-version warnings. |
| Nested shape and reward-size validation | **Implemented** | Explicit v2 rejects unknown arena/exit/tier keys; legacy reports them; an inclusive overlap sweep rejects any score that could exceed 100 durable reward steps. |
| Permission separation | **Implemented; role matrix pending** | Player leaves plus separate arena/queue/season/export/reward/run-investigation/recover/validate/debug/open/stop duties. |
| Safe health/debug pages | **Implemented; log review pending** | Bounded database, queue, arena, season, restoration, reward, hook, and runtime summaries. |

### GUI, placeholders, API, and operations

| Capability | Status | Notes |
| --- | --- | --- |
| Typed-holder GUI | **Implemented; exploit matrix pending** | Inventory ownership is not inferred from title/lore; click/drag cancellation and 750 ms open limiter. |
| PlaceholderAPI expansion | **Implemented; server verification pending** | All-time, active season, top ten, capacity, queue, and active-run values; game state is served from immutable async-safe publications. |
| Read-only Bukkit API | **Implemented** | Release, UUID player/all-time leaderboard, active run, capacity, active season, per-player queue, and bounded recent-run views on the primary thread. |
| Lifecycle events | **Implemented** | Cancellable run start/reward-plan plus jump, run end, and personal-best events. |
| Structured audit | **Implemented; log review pending** | Plugin/run/queue/reward/season/export events, arena edits, recovery retries, admin stop/open/reload/validate, result categories, player operator UUIDs, and explicit player/system actor categories are covered without raw commands or configuration. |
| Release health policy | **Partial** | Metrics and bounded shutdown exist; server-level alerting/runbook ownership remains operational work. |

## Known limitations and incomplete work

These are not hidden defects; they are explicit design or release boundaries that must be accepted or fixed.

### P0 — finish before event approval

1. **Commit the machine-qualified candidate.** Clean build, archive verification, final hash/size/test record, byte-identical rebuild, Paper 26.2 build-60 full-stack/standalone console smoke, test-server sync, and plugin-log review pass. The working tree still needs a final reviewed commit before source identity is immutable.
2. **Repeat migration on a disposable copy of all 100 live rows.** Compare every UUID/score and top-ten position, verify both databases, and prove `_resources` stayed unchanged.
3. **Complete the human abuse/recovery matrix.** Queue concurrency, GUI clicks/drags, all cleanup causes, world protection, signs, containers, tile PDC, conflicts, missing worlds, clean stop, and kill/restart remain server acceptance work.
4. **Rehearse reward uncertainty with staging accounts.** Test PENDING discovery/abandon and UNKNOWN downstream investigation/resolution. Assign a named operator and never interpret a resolution as a replay request.
5. **Rehearse rollback.** Restore the prior JAR and matching data folder together, measure recovery time, and document that database rollback discards later scores.
6. **Resolve distribution rights.** No approved license file is present. Keep the build private/custom unless relevant rights holders authorize redistribution.

### P1 — durability and operator workflow

#### Extend staff run/reward investigation

**Core retained-run queries implemented; export/annotation workflow proposed.** Build 003 now has a separate investigation permission and redacted commands for recent runs by status, player UUID, exact run UUID, arena, or season, plus one-run inspection with linked reward-plan status. The repository additionally supports exact release and a complete start-time interval capped at 366 days. All results are newest-first and capped at 100, and every successful query audits the operator UUID, safe filter, and count.

Remaining design work:

- export of a bounded investigation bundle without names, raw commands, or absolute paths;
- explicit annotations/operator decisions with immutable audit events;
- an approved in-game syntax or public-API contract for the already bounded release/time filters;
- query rate limiting if production history/role usage makes it necessary.

Do not add username ownership lookup or a “rerun reward” button.

#### Complete administrative audit coverage

**Implemented; server log review pending.** Admin stop/open/reload/validate now emit safe result events with player operator UUID when applicable and an explicit `player`/`system` actor category. Existing arena edits, restoration recovery, queue controls, season transitions, exports, and reward/run investigations use the same actor convention. Full coordinates, raw configuration, structures, and command bodies remain excluded.

#### SQLite maintenance and shutdown runbook

- Add an operator maintenance/status command for database size, WAL/journal state, backup inventory, and read-only `quick_check` scheduling.
- Define backup retention; automatic migration backups currently accumulate intentionally rather than being deleted without policy.
- Document that queued operations settle on close timeout, while an already-running SQLite JDBC call may outlive an unsuccessful close if interruption is ignored.
- Add disk-space preflight and clearer full/read-only filesystem categories without exposing absolute paths to players.

#### Persist minimal crash-recovery player return evidence

**Implemented; destructive rehearsal pending.** Player/run/arena-owned state is fsynced before plugin mutation or arena teleport. It stores no inventory or names, quarantines an affected online player while exact retained-run ownership is verified, applies terminal death/teleport semantics without replaying superseded state, and deletes only the exact record after applicable cleanup succeeds. Malformed, orphaned, or ambiguous evidence is retained and fails closed; an exact valid copied record is intentionally treated as indistinguishable operator-owned evidence. The remaining gate is a disposable hard-process-kill matrix at each write/mutation/teleport/cleanup boundary.

Valid pending evidence withholds its exact arena across restarts; unreadable evidence with unknown arena ownership withholds all arenas. `/walk admin recover` reissues ownership lookups for online affected players. There is intentionally no online discard button: unverifiable evidence uses the documented stopped-server, backup-first matching-data restore/manual-resolution procedure and is preserved outside the active journal rather than deleted.

#### Close equipment and attribute movement-fairness gaps

**Implemented; custom-item beta matrix pending.** Build 003 rejects a changed movement-speed base and every modifier except the exact vanilla sprint modifier at admission, immediately before activation, and during the one-second active sweep. A newly ineligible run ends without rewards, and held-slot changes are denied while active. WalkThePlank does not delete, rewrite, or serialize equipment. Test ordinary sprinting plus standard/custom Paper/PDC equipment and external attribute providers before event approval.

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

#### Milestones and accessibility

Provide native title/action-bar/sound/particle celebrations at configured scores, with rate limits and per-player reduced-particle/sound toggles. Prefer cosmetic feedback over console economy commands.

#### Combo or flawless mode

Create a distinct category for time-target streaks; do not retrofit multipliers into the classic personal-best score.

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
- Add score-rate anomaly logging with evidence and no automatic ban.
- Consider hash-chained or signed audit segments if tamper evidence becomes a requirement.
- Add privacy retention policy for run history/audit exports and a documented operator deletion workflow that preserves referential integrity.
- Add explicit confirmation/nonces for every destructive future GUI action; never authorize from title, lore, material, or slot alone.
- Continue rejecting unsafe paths, symlinks, control characters, oversized values, nested audit data, and malformed identifiers.

## Testing investments

### Reproducible Paper integration harness

Automate a disposable Paper 26.2 profile with scripted players or a test plugin for enable/disable, permissions, commands, PlaceholderAPI, lifecycle events, queue concurrency, gameplay cleanup, and log assertions. Never point it at `_resources` directly.

### Fault injection

Add controlled failpoints around:

- journal temp write/fsync/rename and world placement/restoration;
- teleport/player snapshot restoration and scheduler rejection;
- run start/completion, reward plan/claim/outcome, retention/tombstone insertion, and export rename;
- audit append/rotation and config candidate/rollback;
- SQLite lock, disk-full/read-only behavior, timeout, and interrupted close.

Every accepted future must settle, every ambiguous external side effect must remain visible, and no fault may trigger blind replay.

### Load and soak tests

Exercise maximum configured arenas, queue churn, GUI spam, placeholder reads, leaderboard reads, score writes, audit rotation, and journal cleanup for several hours. Record main-thread time, database latency, memory, pending mutations, queue fairness, retained-run growth, and quarantine count.

### Upgrade and disaster-recovery matrix

Test fresh install, the supplied legacy data, already-migrated schema v2, missing-version config, invalid/newer schema, older candidate rollback, process kill at each durable boundary, missing world, corrupt journal, and restored backup. Verify exact row/UUID/top-ten preservation throughout.

## Deliberate non-goals

Unless a future requirement changes with a documented design review, do not add:

- MySQL/MariaDB support or a remote database driver;
- username-based ownership, automatic CMI database import, or online UUID lookup;
- automatic replay of `PENDING`, `DISPATCHING`, or `UNKNOWN` reward work;
- raw reward commands in SQLite, exports, audit, health, or public API;
- direct live-database access for websites;
- mutable session/database handles in the Bukkit API;
- bundled Paper, Bukkit, PlaceholderAPI, CMI, or CMILib classes;
- compatibility shims for unsupported 1.20.x, 1.21.x, or 26.1.x servers in the build-003 artifact.

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
