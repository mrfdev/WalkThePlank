# WalkThePlank v2.4.7-016 beta and event checklist

This is the mandatory human/server acceptance plan for `1MB-WalkThePlank-v2.4.7-016-j25-26.2.jar`.

Do not treat implemented code, a successful Gradle build, or a previous release's smoke test as production approval. The candidate is approved only after this checklist is completed against the exact frozen JAR, a production-like Paper 26.2 server, a disposable copy of the live data, and the intended external reward providers.

Never test migration directly against `_resources/InfinityParkour/`. Never open the production event server for players while destructive, crash, reward, or rollback tests are running.

## Candidate record

Fill this table from the final clean build. Do not copy values from any prior candidate.

| Property | Candidate value |
| --- | --- |
| Semantic version/build | `2.4.7-016` |
| Expected filename | `1MB-WalkThePlank-v2.4.7-016-j25-26.2.jar` |
| Git commit | Full clean commit embedded in the final JAR; record in a new annotated build-016 RC tag and operator release archive |
| Build timestamp | Not embedded; record externally with the candidate evidence |
| File size | Record in annotated candidate tag and operator release archive |
| SHA-256 | Record in annotated candidate tag and operator release archive |
| Java runtime | Oracle Java `25.0.2+10-LTS-69` (aarch64) |
| Paper version/build | Require Paper 26.2 build 62 beta or newer; API `26.2.build.62-beta` |
| Automated test result/count | Record from the final clean-commit build |
| `verifyReleaseJar` result | Require PASS on two clean builds with byte-identical outputs |
| Test-server sync result | Require staged SHA-256 to match and only build 016 to be active |

Build 010 completed its controlled scenarios, reproducibility/freeze, standalone/full-stack Paper smoke, live-copy schema-v3 preservation, and test-server synchronization and is tagged `v2.4.1-010-rc.1`. Builds 011–013 corrected the Paper 26.2 movement baseline, durable activation ownership, within-block forward activation, and velocity cancellation loop. The build-013 real-client pass completed five consecutive forward/backward/sprinting/jumping starts and exposed an idempotency gap in late block-cleanup callbacks. Build 014 preserved newer exact ownership and introduced recursively non-italic pastel GUI tooltips. Its follow-up client rehearsal completed six rapid same-arena runs with only the expected bounded reservation/settled messages and no lifecycle failure, quarantine, or blocked next run; the operator also approved the visual treatment of all three menu icons. Build 015 finished the Play-tooltip proofreading. Build 016 replaces the assumed “glowing platform” description with a bounded label derived from `parkourBlocks`. Repeat the automated candidate gates and Paper startup/reload/shutdown checks against the final clean build-016 artifact.

## Known build-003 migration evidence

Build 010's persistent live-copy smoke preserved 100 rows, 100 UUIDs, score sum 4256, maximum score 149, the unchanged Classic top ten, and `PRAGMA quick_check = ok`. Build 016 has no database-schema change, but repeat every preservation check below against its frozen candidate. Historical evidence is not a sign-off.

## 1. Source and release integrity

- [x] Confirm `gradle.properties` says version `2.4.7`, build `016`, Java `25`, Paper `26.2`, and exact Paper API `26.2.build.62-beta`.
- [x] Confirm the wrapper/build dependencies remain Gradle 9.6.1, Shadow 9.5.1, PlaceholderAPI 2.12.3, SQLite JDBC 3.53.2.0, and JUnit 6.1.2 unless a newer version has been deliberately reviewed and recorded.
- [x] Confirm `plugin.yml` retains Bukkit name `InfinityParkour`, `api-version: 26.2`, and main class `com.mrfdev.walktheplank.WalkThePlankPlugin`.
- [x] Confirm the production plugin uses `JavaPlugin#getLifecycleManager` with `LifecycleEvents.COMMANDS`, keeps `plugin.yml` rather than adding `paper-plugin.yml`, and contains no legacy `PluginCommand` executor/tab-completer registration.
- [x] Confirm `plugin.yml` soft-depends on PlaceholderAPI plus the configured live reward-root providers `CMI`, `UltimateFireworks`, and `PyroWelcomesPro`. Confirm `CMILib`, `Vault`, and `PyroLib` are not WalkThePlank dependencies or soft dependencies.
- [x] Confirm `_resources/` and `servers/` remain ignored and no live/server data is staged for Git.
- [ ] Record a clean `git status`, require the embedded full commit to match `HEAD`, and record the annotated RC tag.
- [ ] Run `./gradlew clean freezeCandidate` with Java 25.
- [ ] Require zero plugin compiler warnings. The build fails if `-Xlint:all -Werror` finds a warning.
- [ ] Record the final test result and test count; do not reuse an old count.
- [ ] Run `./gradlew releaseInfo` and require the exact version/build/filename.
- [ ] Run `./gradlew verifyReleaseJar` and record success.
- [ ] Confirm the shaded JAR includes `org/sqlite/JDBC.class` and `META-INF/services/java.sql.Driver`.
- [ ] Confirm the shaded JAR contains no `com/mysql/`, `org/mariadb/`, `org/bukkit/`, `io/papermc/paper/`, or `me/clip/placeholderapi/` classes.
- [ ] Confirm the JAR contains no `.db` or `.sqlite` file and no `_resources` or server directory.
- [ ] Confirm embedded `plugin.yml`, `build-info.properties`, and manifest all identify v2.4.7 build 016, Java 25, Paper 26.2 build 62 minimum, and the exact artifact name.
- [ ] Record final size, SHA-256, source commit, runtime, test result, and smoke evidence in the annotated RC tag and operator archive.
- [ ] Copy the JAR by checksum and prove the staged copy is byte-identical.
- [ ] Confirm only one InfinityParkour/WalkThePlank JAR is active. Move every prior build to `plugins-disabled/walktheplank/`.

## 1A. Test-only scenario isolation and controlled Paper profile

The scenario system is release tooling, not a production feature. Never copy either `TEST-ONLY-` artifact to a live or ordinary staging server, and never run destructive scenarios against `_resources` or the event server.

### Artifact and byte-isolation gates

- [ ] Run `./gradlew scenarioArtifacts verifyProductionScenarioIsolation` with Java 25 and require both tasks to pass.
- [ ] Require these two test-only files beneath `build/scenario-artifacts/`:

  ```text
  TEST-ONLY-1MB-WalkThePlank-ScenarioHarness-v2.4.7-016.jar
  TEST-ONLY-1MB-WalkThePlank-v2.4.7-016-Failpoints.jar
  ```

- [ ] Require the production file to remain `build/libs/1MB-WalkThePlank-v2.4.7-016-j25-26.2.jar`; prove the instrumented file has a different path and checksum and did not replace it.
- [ ] Run `./gradlew verifyReleaseJar` separately and require the production JAR to contain no `com/mrfdev/walktheplank/scenario/` class, `WalkThePlank-Test-Artifact`/`WalkThePlank-Test-Canary` manifest attribute, scenario command, failpoint system-property prefix, scenario canary, Java agent entry point, or any of the 24 failpoint identifiers.
- [ ] Require positive controls: the harness must be detected as test-only, the instrumented copy must contain the injected bridge/canary and all 24 identifiers, and the isolation task must reject a deliberately scanned test artifact as production-safe.
- [ ] Inspect the scenario harness archive. Require an independent `plugin.yml` named `WalkThePlank-ScenarioHarness`, hard dependency on `InfinityParkour`, classes only under `com/mrfdev/walktheplank/scenario/harness/`, and no shaded WalkThePlank, SQLite, Paper/Bukkit, or PlaceholderAPI classes.
- [ ] Start the harness once against the unmodified production JAR in a disposable profile and require fail-closed enable. In separate fixtures, omit the profile flag, root, nonce, or marker; change the nonce; use a relative/mismatched working directory or data path; symlink the build/runtime/profile/marker; and point at a non-generated server path. Require both the early failpoint bridge and harness to refuse every invalid combination before exposing destructive controls.
- [ ] Run the normal unit suite and require the reflection listener-contract matrix to pass, locking every reviewed `EventHandler` priority and `ignoreCancelled` value.

### Automated two-start and dependency/lifecycle profile

- [ ] Run `./scripts/run-controlled-scenarios.sh` from a clean disposable profile. Record its exit status, Paper/JVM identity, exact three artifact checksums, and output/log directory.
- [ ] On start one, run without PlaceholderAPI. Require the target and harness to enable once, the API/headless baseline to pass, `/walk admin reload` to pass, terminal target disable to remove its Bukkit service, and a durable restart marker to be prepared before clean stop.
- [ ] On start two, use the same disposable profile with PlaceholderAPI present. Require the restart marker to be consumed for the same target release, the target to enable in the fresh JVM, the built-in expansion expectation to pass, reload and terminal disable to pass again, and clean shutdown to release the database lock.
- [ ] Require the runner to distinguish `WTP-SCENARIO PASS`, `FAIL`, `INFO`, and `PENDING`. Any `FAIL`, missing required PASS, unexpected early exit, timeout, dirty shutdown, or unclassified plugin warning/error is a failed run. A `PENDING` line is an explicit handoff to a real-client test and is never counted as a pass.
- [ ] Preserve separate raw and ANSI-stripped logs for both starts plus the runner summary. Search them for `InfinityParkour`, `WalkThePlank`, `WTP-SCENARIO`, `WARN`, `ERROR`, `SEVERE`, `Exception`, `deprecated`, `modified.*MONITOR`, rejected tasks, database timeouts, and thread-access warnings; classify every match.
- [ ] After each stop/restart, prove the Paper process and listening port are gone before reusing the profile. A timeout or surviving process invalidates later database-lock evidence.
- [ ] Query the stopped disposable SQLite database after both starts and record `PRAGMA journal_mode`, `PRAGMA synchronous`, `PRAGMA foreign_keys`, `PRAGMA user_version`, and `PRAGMA quick_check`. Do not assume WAL or a particular synchronous setting; require `quick_check` to be exactly `ok` and compare row/UUID/top-ten invariants where live-copy data is used.

The automated profile owns artifact isolation, two-process starts, restart-marker persistence, PlaceholderAPI absent/present behavior, target reload, terminal disable/service removal, fresh-JVM enable, Bukkit-service registration, deterministic markers, process/port cleanup, and log assertions. It does not re-enable a disabled instance after Paper unregisters its configured classloader, and it does not emulate a Minecraft network client.

### Real-client scenario responsibilities

- [ ] With two real players, request starts in the same tick/test window and repeat while all arenas are occupied. Require one authoritative start per free arena, deterministic FIFO placement/readiness for the remainder, no duplicate active run, no bypass, and scenario contention evidence for both UUIDs.
- [ ] Record every `SessionEndReason` through `/wtpscenario exits expect <reason> [player]` and the corresponding real action. Require each expected reason exactly once with one terminal state and cleanup; `/wtpscenario exits status` must show no missing reasons.
- [ ] During an active run, run the harness cancel and retarget teleport probes separately. Require cancellation to retain the run/evidence and return false; require a final retarget to be observed without MONITOR mutation, then one verified next-tick `TELEPORT` cleanup.
- [ ] Arm reconnect capture during an active run, disconnect, and reconnect the same client. Require one `QUIT` end, no active run after join, exact applicable player-state/return recovery, and no duplicated score/reward. Treat the harness's player-state line as pending until the client/operator verifies it.
- [ ] Open a captured GUI and perform left click, shift-click, hotbar/number-key, double-click, creative click, and a top-inventory drag. Require each real event to be cancelled and the harness to report all six gestures. Run `/wtpscenario gui stale`; require a new nonce/generation/exact inventory, one accepted-then-invalidated pending action, a live target-scheduler witness, `sentinel-executed=false`, and `gui-stale-action` PASS. The command must use the production scheduled callback/revalidation path and must not fabricate an inventory event or packet.
- [ ] Repeat applicable GUI/teleport/reconnect/exit cases through close, quit, kick, death/respawn, world change, game-mode change, permission revocation, timeout, admin stop, reload, plugin disable, and server shutdown. Match each harness record with WalkThePlank log, audit, run-history, journal, and player-visible evidence.

### Named hard-kill and restart matrix

Use only the instrumented target plus harness in a fresh disposable copy. For each row: record pre-state/checksums/PRAGMAs; arm exactly one point with `/wtpscenario failpoint arm <point> HALT 1 <unique-nonce>`; trigger the real operation; require the failpoint's `REACHED` marker and expected process exit; prove the old process is gone; restart the same profile; inspect logs, SQLite, journals, world/player state, audit, exports/config files, reward provider evidence, and idempotency; then restore a clean fixture before the next point. Also exercise selected reversible points with `THROW` and `BLOCK`/`release` to prove contained-failure and contention behavior without process termination. Do not use those actions for player/restoration journal rename or delete, configuration candidate rename, disk commit, or runtime commit; all seven reject anything except `HALT`.

| Failpoint(s) | Required restart evidence |
| --- | --- |
| `player_journal.after_temp_write`, `.after_temp_fsync` | No unjournaled player mutation is accepted; classify/remove only a recognized pre-commit temp through the normal policy, preserve unknown evidence, and keep run/arena/player state safe. |
| `player_journal.after_rename`, `.after_directory_fsync` | Treat the player-recovery record as committed; verify exact run/player/arena ownership, quarantine until lookup completes, and recover state/return once without duplication. |
| `player_journal.after_delete` | Completed recovery remains idempotent; restart must not replay captured state or strand a false active run. |
| `restoration_journal.after_temp_write`, `.after_temp_fsync` | The block must still be original because placement has not crossed a committed journal boundary; normal recognized-temp handling must not mutate the world. |
| `restoration_journal.after_rename`, `.after_directory_fsync` | A committed record with an unchanged original block is recognized and completed safely, or retained fail-closed if evidence/world is unavailable. |
| `restoration_journal.after_delete` | A completed restoration remains complete and idempotent after restart; no generated block or stale quarantine is recreated. |
| `block.after_place` | The pre-mutation record exists; restart restores the exact original block/tile/PDC snapshot and releases the arena once. |
| `block.after_restore` | Restart recognizes the exact already-restored snapshot and finishes record cleanup without a second destructive mutation. |
| `teleport.after_start` | Durable STARTED/player-recovery evidence remains reconcilable; reconnect verifies ownership, restores state/return once, and never invents a score or reward. |
| `teleport.after_return` | Already-applied return/state cleanup is not replayed destructively; any still-durable record is cleared only after authoritative verification. |
| `score.after_commit` | Completion/score projection remains exactly once after restart. Any eligible reward intent follows its persisted state; score and personal-best totals cannot double. |
| `reward_claim.after_commit` | The claimed step is uncertain/dispatching on restart and must not be automatically replayed; preserve redacted operator evidence. |
| `reward.after_dispatch` | Correlate downstream provider evidence manually; persist/report uncertainty and never infer success or replay merely because command dispatch returned. |
| `reward_outcome.after_commit` | The terminal step/plan outcome remains durable and cannot dispatch again after restart. |
| `export.csv.after_rename` | Preserve and classify the possible CSV-without-JSON result. The existing files remain valid, but the pair is not falsely reported as one atomic transaction. |
| `export.json.after_rename` | Validate both completed files and their UUID/rank contents; repeated export may replace the pair safely without corrupt partial files. |
| `config.backup.after_rename`, `config.candidate.after_rename` | Inspect `config.yml`, backup, and temp files while stopped; require one parseable intended/previous configuration and no silent fallback to an empty/default file. Retain unexpected temp/orphan evidence for investigation. |
| `config.after_disk_commit` | On restart, the validated on-disk candidate is authoritative; verify fingerprint/content and never assume the pre-crash in-memory bundle survived. |
| `config.after_runtime_commit` | The committed candidate loads consistently after restart and repeated admin reload remains idempotent. |

- [ ] Run queue contention while a `BLOCK` point holds the repository worker and while two starts are requested. Require bounded pending work, main-thread responsiveness, fair/no-duplicate outcome after release, and no future left unsettled.
- [ ] Run hard kills with active player, queued/ready player, two generated blocks, and each reward state relevant to the point. Require exact run IDs/idempotency keys and no blind replay.
- [ ] Record that `Runtime.halt`/process kill does **not** emulate storage-device cache loss, filesystem corruption, or host power loss. Do not use this matrix to claim power-loss durability.
- [ ] Record known output limitations rather than hiding them: CSV and JSON are independently atomic but not one atomic pair; a CSV orphan can remain after its rename point, and interrupted export/config temporary files are not automatically cleaned at startup today.

## 2. Backup and disposable live-data setup

- [ ] Stop Paper before copying any database or plugin data.
- [ ] Hash `_resources/InfinityParkour/database.db` and record the full hash.
- [ ] Copy the complete live/reference `InfinityParkour` folder to a disposable staging profile; include config, translations, database, and any accompanying CMI reference database needed only for comparison.
- [ ] Make a second operator-controlled backup of that disposable copy before first start.
- [ ] Record the pre-migration database size, `PRAGMA user_version`, table list, row count, UUID count, score sum, maximum score, and top ten.
- [ ] Require the expected baseline: 100 score rows, 100 valid UUIDs, score sum 4256, and maximum score 149.
- [ ] Confirm `PRAGMA quick_check` returns exactly `ok` before migration.
- [ ] Confirm the copied CMI database is not opened or modified by WalkThePlank.
- [ ] Confirm no external profile API request is made.

Useful read-only baseline queries:

```sql
PRAGMA quick_check;
PRAGMA user_version;
SELECT COUNT(*) AS rows,
       COUNT(uuid) AS uuid_rows,
       SUM(score) AS score_sum,
       MAX(score) AS max_score
FROM scoreboard;
SELECT id, uuid, username, score
FROM scoreboard
ORDER BY score DESC, id ASC
LIMIT 10;
```

## 3. Schema-v3 migration, backup retention, categories, preferences, and UUID preservation

- [ ] Start the frozen candidate once against the disposable legacy database.
- [ ] While that repository is open, start a second disposable WalkThePlank repository/server against the same database. Require immediate safe startup failure naming existing ownership, no migration/write from the competitor, and uninterrupted operation of the first instance.
- [ ] Confirm the sibling `<database-filename>.walktheplank.lock` is a regular non-symlink file. Replace it with a symlink and a directory in separate stopped-server fixtures and require fail-closed startup without touching either target.
- [ ] Confirm `.recovery-durability.lock` and `.operations-io.lock` are regular non-symlink files in `plugins/InfinityParkour/`. Replace each with a symlink and a directory in stopped disposable fixtures and require fail-closed startup before journal/audit inspection. Never delete any of the three lock files to clear ownership; an unlocked file may remain normally, and deleting a still-locked Unix pathname can defeat exclusivity.
- [ ] Stop the first instance with a successful repository drain, then start a replacement against the same database. Require successful lock acquisition even if the unlocked lock file remains on disk; never make deletion of that file part of normal startup.
- [ ] Require a startup log naming a verified sibling backup like `database.db.pre-migration-v3-<epoch>.sqlite`.
- [ ] Confirm the backup exists, is non-empty, and independently returns `PRAGMA quick_check = ok`.
- [ ] Require `PRAGMA user_version = 3` after migration.
- [ ] Confirm `schema_migrations` records schema versions 1, 2, and 3.
- [ ] Confirm these tables exist: `scoreboard`, `schema_migrations`, `seasons`, `season_scores`, `run_history`, `reward_plans`, `reward_steps`, `reward_tombstones`, `player_preferences`, `category_scores`, and `run_category_scores`.
- [ ] Confirm required unique/ranking/status indexes and foreign keys exist.
- [ ] Corrupt a disposable durability-table constraint, foreign key, and same-named index definition one at a time; require fail-closed startup rather than silent acceptance or replacement.
- [ ] Re-run the baseline aggregates and require exactly 100 rows, 100 UUIDs, sum 4256, and max 149.
- [ ] Compare the complete pre/post UUID-to-score mapping and top-ten ordering, not only aggregates.
- [ ] Confirm every UUID remains associated with its original last-known name/score; no username merge occurred.
- [ ] Confirm `PRAGMA quick_check` returns exactly `ok` after migration.
- [ ] Stop and restart again. Require no second migration and no unnecessary new pre-migration backup.
- [ ] Re-hash `_resources/InfinityParkour/database.db` and require its original hash to be unchanged.
- [ ] On another disposable database, add a username-only/null-UUID row and confirm it remains unresolved rather than being guessed from CMI or the internet.
- [ ] Confirm an unresolved row remains visible in ranking but cannot satisfy UUID player stats.
- [ ] Confirm UUID-only export refuses a snapshot containing any unresolved row and creates no partial CSV/JSON pair.
- [ ] On disposable corrupt cases, confirm malformed nonblank UUIDs, duplicate UUIDs, incompatible tables/indexes, and a schema version newer than 3 disable the plugin safely without mutating the source copy.
- [ ] Create more exact automatic migration backups than the configured retention. Require every candidate to pass `quick_check`, only the oldest excess automatic files to be pruned, the doctor to report retained/configured/pruned counts, and a nonmatching operator-named backup to remain byte-identical.
- [ ] Replace one exact automatic backup candidate with a corrupt file, symlink, and directory in separate stopped fixtures. Require fail-closed startup and no deletion of any other backup.
- [ ] Complete/retry one run with Combo and Flawless projections. Require one Classic projection, exact idempotent category rows, no duplicate, and unchanged Classic table/top-ten semantics.
- [ ] Save each particle mode and sound/title toggle, restart, and require UUID-owned preferences to survive without creating a Classic leaderboard row.
- [ ] Issue particle, sound, and title changes rapidly without waiting for earlier replies. Require the single SQLite writer to merge all three field updates without a lost update, then reconnect and restart to confirm the combined state.

## 4. Configuration version 2 and safe reload

- [ ] Run `/walk admin validate` on the intended live config and translations; record the SHA-256 config fingerprint, all warnings, and zero errors.
- [ ] Confirm the intended arena coordinates/worlds/exits, reward tiers, queue settings, permissions, and database filename match event policy.
- [ ] On a disposable config with no `configVersion`, confirm validation calls it legacy, inherits missing bundled defaults in memory, warns about legacy/unknown keys—including nested arena, `endPos`, and reward-tier keys—and does not rewrite the file. Unknown keys are ignored, but a legacy MySQL enable flag set to `true` must still fail closed.
- [ ] Confirm an explicit `configVersion: 2` makes an unknown/typo key a validation error at the top level and inside arena, `endPos`, and reward-tier mappings. A present non-mapping `endPos` must fail even when custom exit use is disabled.
- [ ] Set required arena coordinates, optional yaw/pitch, reward bounds, and `useCustomEndPosition` explicitly to YAML null or the wrong scalar type; require validation errors rather than silent zero/default coercion.
- [ ] Confirm an explicit version other than `2` is rejected.
- [ ] Confirm an invalid YAML/config/translation reload reports failure and leaves the previous valid runtime settings active.
- [ ] Confirm a successful reload closes menus, ends/restores active runs, aborts pending starts, drains the queue, and rebuilds the arena pool.
- [ ] Confirm changing the database setting during reload warns that a restart is required and does not switch the active repository mid-run.
- [ ] Confirm `database.type: MYSQL`, a legacy enabled MySQL flag, an absolute SQLite filename, `..` traversal, a symlinked data/database path, a directory as the database target, and an out-of-range busy timeout are rejected.
- [ ] Confirm reward commands with CR/LF, an unsafe root, or more than 2048 characters are rejected without echoing the full command in safe diagnostics.
- [ ] Confirm an omitted `rewards.allowedCommandRoots` inherits the bundled list. Confirm the effective list contains 1–32 strings, trims/lowercases canonical roots, and rejects an explicit empty/non-list value, 33 entries, duplicates after normalization, or characters outside `[a-z0-9][a-z0-9:_-]{0,127}`.
- [ ] Put an otherwise valid and registered command outside `rewards.allowedCommandRoots`; require a validation error whether `runFinishCommands` is true or false. Add the exact root and require normal validation, then remove unused bundled provider roots before freezing event configuration.
- [ ] Confirm unavailable command roots are an error when rewards are enabled and only a warning when the master reward switch is disabled.
- [ ] Confirm overlapping reward ranges produce a warning and are accepted only when the operator intentionally wants combined commands.
- [ ] Confirm inclusive overlapping ranges that can produce exactly 100 non-empty reward commands for one score are accepted, while 101 are rejected before activation.
- [ ] Confirm the live `JACK_O_LANTERN` and legacy `STONE` platform choices validate. Confirm `FURNACE`, `BLAST_FURNACE`, `BARREL`, `CHEST`, `TRAPPED_CHEST`, `NOTE_BLOCK`, and representative 26.2 copper-chest/shelf variants are rejected by the non-deprecated stateful/workstation policy.
- [ ] Load the unchanged live `translations.yml` and confirm every unmarked value retains backward-compatible legacy `&` formatting; the upgrade must not require a bulk translation rewrite.
- [ ] Confirm a trusted value prefixed exactly with `minimessage:` renders as MiniMessage, while the same unmarked text remains a legacy template. For the inventory window title specifically, require formatting to be stripped and the validated `mainGui.titleColor` hex color to be applied.
- [ ] Load the unchanged live `mainGui.title` and `WHITE_STAINED_GLASS_PANE` value. Require readable `#111827` title text and the light-blue 1MB border in memory without rewriting `translations.yml`; separately verify `#000000`, a custom valid hex color, and fail-closed rejection of an invalid color.
- [ ] Put `&c`, `§c`, `<red>`, `<click:run_command:'/op test'>`, and `{{otherToken}}` in disposable dynamic season/database values. Require every value to render literally with no injected color, tag, click/hover event, or secondary placeholder expansion.
- [ ] Combine legacy and `minimessage:` prefix/message templates in both supported orders. Require formatting to remain bounded and dynamic replacements to remain literal; an untrusted dynamic value must never activate the marker.

## 5. Paper startup and dependency matrix

- [ ] Start with Java 25 and Paper 26.2 build 62 beta or newer.
- [ ] Require `InfinityParkour v2.4.7-016` to enable once with 100 preserved scores.
- [ ] Attempt startup on Paper 26.2 build 61 and on a 26.1.x runtime in disposable profiles. Require WalkThePlank to refuse enable before opening its data directory, with one bounded target-versus-runtime diagnostic.
- [ ] Require no WalkThePlank exception, deprecated-method warning, task rejection, linkage error, or unexpected startup/shutdown warning. The JVM's JOML `sun.misc.Unsafe` warning is emitted by Paper's `joml-1.10.8.jar`, not this plugin.
- [ ] Break configuration or journal initialization in a disposable profile and require startup to abort. The disable audit says `startup_completed:false` and `clean:false`; the log explicitly refuses a clean-restoration claim.
- [ ] Run `/plugins` or equivalent and confirm no duplicate plugin-name conflict.
- [ ] Run `/walk info`; verify release, artifact, Java/Paper target, scores, and arenas.
- [ ] Run `/walk debug overview`, `health`, `hooks`, `commands`, `permissions`, `placeholders`, `config`, and `all`; confirm every page is bounded, readable, and free of raw commands, credentials, absolute database paths, or stack traces.
- [ ] Start once without PlaceholderAPI. Require normal core operation and no linkage error.
- [ ] Start once with the intended PlaceholderAPI version and require the built-in expansion to register.
- [ ] With each configured reward provider installed, cold-start and confirm its soft dependency orders it before WalkThePlank's synchronous command-root validation. Treat hook presence as informational only; WalkThePlank must not link to or shade the provider API.
- [ ] Remove each configured reward provider in a disposable profile. With rewards enabled, require the missing command root to fail configuration safely; with the master reward switch disabled, require a warning and otherwise normal core startup.
- [ ] Record the staged provider versions. The previous build-003 profile had CMI 9.8.8.5 and CMILib 1.5.9.9, but neither is required unless the chosen `finishCommands` use its commands/content.
- [ ] With live rewards enabled, require every intended command root and referenced CMI kit/firework/welcome/effect to exist and be tested separately.
- [ ] Confirm the MySQL/MariaDB driver is neither required nor loaded.

## 6. Command and permission review

- [ ] On Paper 26.2, require startup to register one `walktheplank` Brigadier root plus `/walk`, `/infinityparkour`, and `/infp` aliases without duplicate-command, lifecycle, or legacy-command warnings.
- [ ] Inspect client syntax/suggestions: online-player targets must use the single-player Paper argument; run/season/plan/player ownership IDs must reject non-UUID input before execution; list limits must reject values outside 1–100; reward step indexes must reject negatives.
- [ ] Confirm arena/season suggestions refresh from current validated runtime state after `/walk admin reload`, and permissions are re-evaluated for visibility and again immediately before asynchronous or mutating work.
- [ ] Confirm `remove`, reward `resolve`, and reward `abandon` cannot execute without their literal `confirm` node. A partial command must perform no mutation or command dispatch.
- [ ] As a default player, test `/walk`, `play`, every queue action, `leave`, `stats classic|combo|flawless`, `top all-time|season|combo|flawless`, `info`, and `help`; require `/walk settings` to be hidden from suggestions and denied.
- [ ] Give a player only `infinityparkour.preferences`; require `/walk settings` plus particles full/reduced/off and sounds/titles on/off, while denying play, stats, top, and every admin action.
- [ ] Give a normal player `infinityparkour.preferences`, verify `/walk settings`, then revoke it while the player remains online; require command visibility and execution to fail immediately without changing stored preferences.
- [ ] Confirm `/walk help` and tab completion show only permitted commands.
- [ ] Confirm `/walk admin help` and admin tab completion show only granted administrative leaves.
- [ ] Test console-safe forms and player-only rejection for GUI/location-taking commands.
- [ ] Test compatibility `/walk open`, `/walk reload`, `/walk version`, `/infp`, `/infinityparkour`, and console `/walk <exact-online-player>`.
- [ ] Confirm unknown syntax returns bounded usage/help and never throws.
- [ ] Confirm exact-name target matching; partial/offline names do not select an unintended player.
- [ ] Give a role only `infinityparkour.admin.validate`; require validate access but deny arena edits, reload, health, seasons, exports, queue controls, and rewards.
- [ ] Give a role only `infinityparkour.admin.queue`; require queue controls but deny every other admin action.
- [ ] Repeat isolated-role tests for `admin.arena`, `admin.season`, `admin.export`, `admin.recover`, `admin.stop`, `admin.open`, `admin.debug`, `admin.reward`, and `admin.investigate`.
- [ ] Confirm a remapped privileged permission cannot equal any player permission and that remapped leaves are explicitly granted by the permission manager.
- [ ] Revoke `leavearena` during an active run and confirm `/walk leave` still releases the player safely.
- [ ] Revoke `play` while queued and confirm queue refresh removes the player without reserving an arena.
- [ ] Confirm non-operators do not inherit `infinityparkour.admin` and operators do not accidentally bypass the intended beta role test.
- [ ] Test `/walk admin reward list [status] [limit]`; require defaults `UNKNOWN`/20, all seven documented statuses, newest-first order, and strict limit 1–100.
- [ ] Test `/walk admin reward inspect <plan-uuid>`; require zero-based indexes and redacted root/hash evidence without raw arguments.
- [ ] Test `/walk admin reward resolve <plan-uuid> <step> <succeeded|failed|skipped> confirm`; require an `UNKNOWN` plan/step, literal confirmation, derived plan state, an operator audit event, and no command dispatch.
- [ ] Test `/walk admin reward abandon <plan-uuid> confirm` on a wholly `PENDING` plan; require every remaining step to become `SKIPPED`, terminal `ABANDONED` state, an operator audit event, and no command dispatch.
- [ ] Give a role only `infinityparkour.admin.investigate`; require `/walk admin run` help/list/inspect/player/arena/season, but deny reward resolution, debug, exports, season changes, and every mutating staff command.
- [ ] Test `/walk admin run list [started|completed|aborted|unknown|all] [limit]`; require default `all`/20, newest-first order, strict limits 1–100, bounded lines, and no player names, raw reward commands, absolute paths, stack traces, or unbounded output.
- [ ] Test `/walk admin run inspect <run-uuid>` against a retained rewarded run; require exact run/player UUID, arena, timestamps, score/reason, release, season, and linked plan UUID/status. A missing/invalid UUID must be a bounded non-error response.
- [ ] Test `/walk admin run player <player-uuid> [limit]`, `arena <arena-id> [limit]`, and `season <season-uuid> [limit]`; require exact matching only. A player name, partial UUID, or another player's UUID must never be treated as ownership by name.
- [ ] Query a retained `UNKNOWN` interrupted run and correlate its run/reward evidence without dispatching or replaying anything.
- [ ] Confirm every successful run investigation, including zero results, writes `run.investigation` with player operator UUID when applicable, a safe bounded filter, and result count—but no name, raw command, or filesystem path.
- [ ] Run `/walk admin doctor` from console and from a player granted only `infinityparkour.admin.debug`; require permission-filtered help/tab completion and deny a default player.
- [ ] Require doctor to identify the exact source commit and clean/dirty state, Java/Paper compile targets versus runtime, optional hooks, and configured command-root availability without exposing provider commands.
- [ ] Require doctor to report queue/task health, restoration journal/quarantine counts, player-recovery counts, and uncertain reward totals consistently with `/walk admin status` and `/walk debug health`.
- [ ] Require doctor to acknowledge the asynchronous SQLite probe immediately, then report `quick_check`, database/WAL byte counts, retained/configured/pruned migration-backup counts, audit verified/healthy sequence/anchor state, and non-negative latency without blocking command handling or the primary server thread.
- [ ] Seed recognizable names, coordinates, paths, reward commands, credentials, SQL fragments, and exception text in a disposable fixture/failure. None may appear in doctor success or failure output.

## 7. GUI and inventory abuse

- [ ] Open the real GUI and confirm all 54 slots: readable near-black `#111827` title text, light-blue stained-glass panes only on the 26 outer-frame slots, centered tutorial/play/stats actions at 20/22/24, and 25 unused inner slots left empty.
- [x] Inspect tutorial, play, and Top 10 tooltips. The operator approved the visual treatment of all three icons on 2026-07-20: proofread wording, pale-blue names, readable white/soft-gray body text, pink warning/label text, gold click actions, and no italic text.
- [ ] With the live `JACK_O_LANTERN` setting, require the tutorial to say “jack o'lantern” rather than “glowing platform.” In a disposable reload, change the sole `parkourBlocks` entry to `EMERALD_BLOCK` and require “emerald block”; then restore the live setting.
- [ ] Put nested `<italic>` tags in a disposable custom GUI title/lore and reload. Require every plugin item component to remain explicitly non-italic while its other safe MiniMessage styling remains intact.
- [ ] Load the exact historical live tutorial/play/scoreboard definitions. Require the bundled modern text to replace them only in memory without rewriting the file. Change one item definition deliberately and require that genuinely customized item to remain byte-for-byte authoritative.
- [ ] Confirm legacy `{{index}}` lore renders positions 1 through 10 while `{{rank}}` retains competition rank.
- [ ] Confirm top lore is permission-gated and does not leak entries to a player lacking `topcmd`.
- [ ] Rename an ordinary chest/anvil to the same visible title and confirm it cannot trigger plugin actions.
- [ ] While the real GUI is open, test normal click, shift-click, number key, double-click/collect, hotbar swap, offhand swap, drop, creative click, and clicks in both top and player inventory. No item may enter or leave the plugin inventory.
- [ ] Drag across only bottom slots and across any top slot; require top-inventory mutation to be cancelled and no duplication/loss.
- [ ] Spam `/walk` and admin-open. Confirm the 750 ms menu-open limiter remains bounded and reports slow-down without exceptions.
- [ ] Click play repeatedly, while queued, while ready, while a start is pending, and while already active. Require one queue entry/run only.
- [ ] Reload/disable while a GUI is open and confirm it closes cleanly.
- [ ] Open one exact plugin inventory for a different player from a disposable test listener. Require the owner-UUID check to reject every action even though the holder and inventory instance are genuine.
- [ ] Close/reopen the menu, then attempt to deliver a stale click from the prior nonce/generation/inventory. Require no action; the current generation must continue normally.
- [ ] Double-click and send repeated same-tick click variants across actionable slots. Require at most one pending action for the session, duplicate suppression, and no delayed action after close/reopen.
- [ ] Revoke the relevant permission or change queue/readiness/game state after the click event but before its scheduled action executes. Require immediate revalidation against the new authoritative state and no stale authorization.
- [ ] Quit, kick, change world, reload, and disable with a menu/action pending. Require exact-session invalidation, clean closure where applicable, and no later action, item mutation, duplication, or task rejection.

## 8. Queue, capacity, and selection policy

- [ ] With one arena occupied, join at least three players and confirm FIFO positions 1, 2, 3.
- [ ] Confirm duplicate joins do not duplicate/reorder an entry.
- [ ] Leave/rejoin and verify the configured cooldown and fair tail insertion.
- [ ] Free an arena and confirm only the head receives a readiness window; no automatic teleport occurs.
- [ ] Accept with `/walk queue ready` and by the GUI play action; require one durable run start.
- [ ] Let readiness expire and confirm the arena is reassigned fairly without remaining reserved for that player.
- [ ] Disconnect a waiting/ready player and confirm removal with no offline reservation.
- [ ] Change a queued player's permission, game mode, or movement-effect eligibility and confirm refresh removes the ineligible entry.
- [ ] Confirm action-bar position/readiness reminders follow configured timing and stop after leave/start/drain.
- [ ] Pause the queue after at least one readiness claim exists; positions and remaining readiness time must be frozen, ready consumption must be refused without losing the claim, and no new claim may be assigned. Both `/walk queue ready` and the GUI must report the paused state, not falsely claim expiry. Resume and require the preserved remaining window to continue.
- [ ] Resume and confirm assignments continue.
- [ ] Drain and confirm all online queued players are informed.
- [ ] Disable the queue and confirm player actions report disabled while direct play still works when an arena is free.
- [ ] Test two or more arenas and confirm the number of simultaneous runs never exceeds free, non-quarantined arenas.
- [ ] Test `RANDOM` without assuming deterministic order.
- [ ] Test `ROUND_ROBIN` across sorted arena IDs and repeated releases.
- [ ] Test `LEAST_RECENTLY_USED`, including never-used arenas.
- [ ] Test `PINNED` when free, then occupy/quarantine it and require least-recently-used fallback.
- [ ] Confirm diagnostics and PlaceholderAPI report active, available, total, quarantined, queue enabled/paused/total/waiting/ready counts accurately; also confirm the addressed player's queue position/readiness.

## 9. Arena editor and layout validation

- [ ] `/walk admin arena list` accurately shows each configured ID/world/start/custom-exit state.
- [ ] Create a disposable arena at the tester's block and verify block-aligned start, safe ID validation, and default no custom exit.
- [ ] Set its start and precise exit, then clear the exit.
- [ ] Run validate with and without an ID; confirm the optional ID does not skip complete-layout overlap/safety validation.
- [ ] Confirm duplicate IDs, duplicate start blocks, overlapping safety volumes, missing worlds, world-border/build-height violations, insufficient start headroom, invalid IDs, and unsafe materials are rejected before write.
- [ ] For custom exits, reject placement inside any protected arena volume; outside build height/border; without collidable support; without two passable blocks; or touching liquid, waterlogging, portals, fire, campfires, cactus, magma, powder snow, berry bushes, cobweb, dripstone, or wither rose. Rehearse the exact live exit and require acceptance.
- [ ] Confirm editor mutations are refused while the queue is non-empty or any active/pending run, teleport check, recovery lookup/completion, quarantined arena, restoration/player-recovery record, or arena/block lease remains.
- [ ] Confirm removal requires literal `confirm` and the last required arena cannot leave an invalid empty layout.
- [ ] Confirm successful edit writes atomically, creates `config.yml.backup`, reloads, and reports the validated config fingerprint.
- [ ] Force activation failure after an edit and confirm automatic rollback restores the exact prior bytes. If rollback is intentionally made impossible, require a loud failure and stop further edits.
- [ ] Concurrently change `config.yml` during validation in a disposable test and confirm compare-before-write refuses to overwrite the newer file.
- [ ] Confirm symlinked config/backup paths and filesystems without atomic replacement are rejected safely.

## 10. Seasons, all-time scores, and run history

- [ ] Confirm no season exists initially unless explicitly created; all-time scores remain available.
- [ ] Create `Summer 2026` and confirm it is `PLANNED` with a generated UUID.
- [ ] Confirm season names are case-insensitively unique and limited to 1–80 characters.
- [ ] Activate it and confirm only one active season can exist.
- [ ] Complete positive and zero-score runs; require positive all-time/season projection and retained zero-score run without a score row projection.
- [ ] Start a run while the season is active, close the season while it is in flight, then finish. Require that run to remain associated with the captured season and also update all-time.
- [ ] Start a later run with no active season and confirm it affects only all-time.
- [ ] Confirm `/walk top all-time` and `/walk top season` select the intended independent snapshot.
- [ ] With `categories.combo.maximumGapSeconds` set to a testable value, complete a run whose longest streak is shorter than its Classic score. Require `/walk stats/top combo` to record only the maximum streak and Classic to retain the ordinary score.
- [ ] Complete one within-gap run and one run with a late gap. Require only the first final score in `/walk stats/top flawless`; neither may multiply or rewrite Classic.
- [ ] Hit every configured milestone. Require one bounded action-bar notification plus enabled title/sound/particle feedback, and no console command or reward-plan side effect.
- [ ] Repeat milestones with particles `full`, `reduced`, `off`, sounds off, and titles off. Require only WalkThePlank cosmetic output to change, exact persisted settings after reconnect/restart, and identical scoring/category results.
- [ ] Close, reopen to planned, activate, close again, and archive using explicit commands.
- [ ] Confirm an archived season cannot reopen/activate and a non-closed season cannot archive.
- [ ] Attempt every transition with a timestamp before the season's current transition in a repository fixture; require rejection so lifecycle time never moves backwards.
- [ ] Confirm historical season scores remain queryable/exportable after closure/archive.
- [ ] Confirm every run receives a durable UUID and is inserted `STARTED` before the first journaled world mutation.
- [ ] Confirm completion stores player UUID/name, arena, start/end, score, reason, release, captured season, and status.
- [ ] Kill/restart with a persisted unfinished start; require it to become `UNKNOWN`, with no score projection or reward.
- [ ] Confirm duplicate completion for the same run ID cannot project a second score or reward plan.
- [ ] Inject a repository failure after the completion update but before reward-plan insertion, and again after insertion but before transaction commit. Require rollback to leave the run `STARTED`, with no projected score and no plan/steps.
- [ ] Complete a rewarded run, then submit the exact completion/plan request again; require no second projection or dispatch. Change plan/run/key/time, step order, root, or hash and require a conflict. Complete a run without a plan and confirm a later request cannot backfill one retroactively.
- [ ] Exercise retention on a disposable repository; require at most the newest 10,000 prunable `COMPLETED`/`ABORTED` runs while all `STARTED`, `UNKNOWN`, and uncertain-ledger runs remain protected. Confirm no status is guessed merely to make an unresolved row prunable.
- [ ] Prune a terminal rewarded run and require its compact `reward_tombstones` row to retain plan/run/idempotency replay protection; the old full plan may be gone, but re-preparation must be rejected.
- [ ] Confirm health counts retained runs and active season accurately.
- [ ] In a repository fixture, verify combined status/player/run/arena/season/release/start-window filters use exact prepared values, reject a partial or over-366-day time interval, cap at 100, and treat SQL metacharacters as ordinary arena/release text.

## 11. UUID-only exports

- [ ] Export all-time, current season, and a historical season.
- [ ] Confirm both CSV and JSON are produced under `plugins/InfinityParkour/exports/` with safe unique UTC-timestamped names.
- [ ] Compare row count, order, UUID, last-known name, score, competition rank, and timestamp to the selected immutable snapshot.
- [ ] Confirm CSV quoting and JSON escaping with valid underscore-bearing vanilla names. Separately exercise the exporter's formula-prefix defense with a synthetic unit fixture; such a value must never be accepted as a persisted Java player name.
- [ ] Confirm output is fsynced/atomically moved and an injected second-file failure removes the first file rather than leaving an incomplete pair.
- [ ] Confirm an existing destination is never overwritten; a numeric suffix is selected.
- [ ] Confirm an unresolved UUID rejects the entire export.
- [ ] Confirm there is no import command and no username-based fallback.

## 12. Reward configuration, ledger, and uncertainty

Run economic/reward tests only on disposable accounts and a staging economy/inventory.

- [ ] First test with `runFinishCommands: false`; no external command may run, while normal scoring/run history still works.
- [ ] Review every live score range and intentional overlap/gap.
- [ ] Decide and record whether `rewards.onlyOnPersonalBest` must be enabled for the event.
- [ ] Test every intended tier boundary and every external effect exactly once with disposable accounts.
- [ ] Confirm only `FALL` and player `LEAVE` are reward-eligible. Teleport, timeout, quit, reload, shutdown, admin stop, death/game-mode cleanup, and error must not reward.
- [ ] Confirm a zero-score fall/leave persists its run but creates and dispatches no reward plan; positive scores follow the configured eligibility rules.
- [ ] Confirm runtime preparation rejects any root outside the captured allow-list before command-map lookup or dispatch and finalizes its atomically stored redacted plan without partial dispatch. For allowed roots, confirm all are preflighted before the first command and a missing root likewise aborts/finalizes the entire plan without partial dispatch.
- [ ] Confirm a prepared plan stores plan/run UUID, unique idempotency key, step index, command root, and SHA-256 hash—but no raw command text/arguments.
- [ ] Confirm plan IDs are deterministic per run and duplicate plan/run/idempotency keys cannot dispatch twice.
- [ ] Confirm `WalkRewardPlanEvent` can cancel the plan before dispatch; cancellation must skip/finalize it without replay.
- [ ] Confirm each step is durably `DISPATCHING` before Paper receives its command.
- [ ] Confirm a handled command becomes `SUCCEEDED`; an unhandled command becomes `FAILED`; remaining steps are `SKIPPED` after failure.
- [ ] Confirm a thrown dispatch outcome becomes `UNKNOWN`, never success.
- [ ] Confirm success/failure/unknown/partial/abandoned counts appear in health and root/hash outcomes appear in audit without raw commands.
- [ ] Crash after a reward plan is created but before the first claim. Restart must retain the exact all-`PENDING` plan unchanged and must not auto-run it.
- [ ] Find that plan with `/walk admin reward list pending`, inspect it, and use `/walk admin reward abandon <plan-uuid> confirm`; require terminal `ABANDONED`, all steps `SKIPPED`, an operator audit event, and zero dispatches.
- [ ] In a repository fixture, re-submit the exact same complete request for that wholly `PENDING` plan and require it to be recognized as claimable; alter plan/run/key/time, step order, root, or hash and require an idempotency conflict. Confirm no in-game command exposes this internal exact-retry path.
- [ ] Crash after durable `DISPATCHING` but before calling Paper. Restart must mark it `UNKNOWN`, skip later work, and never auto-replay.
- [ ] Crash after the external command has handled but before outcome persistence. Restart must also mark `UNKNOWN`; staff must investigate downstream state.
- [ ] Kill immediately after the atomic completion/reward-intent commit but before the event/first claim. Require one completed run plus one exact all-`PENDING` plan, no automatic replay, and explicit staff inspection/abandon handling.
- [ ] Make the command gateway report a missing root, then throw an ordinary runtime failure during root preflight. Require an atomically stored redacted plan to be finalized/abandoned without dispatch. Separately force reward-intent construction to throw outside that contained preflight path before a request exists; require completion without a plan, bounded audit/log evidence, zero dispatch, and no retroactive plan attachment.
- [ ] Delay a real staging reward provider while the plan is dispatching/finalizing. Require the same UUID to be unable to start or claim another run until its completion barrier clears; another player must remain unaffected.
- [ ] Test repository failure while preparing, claiming, recording, and finalizing; require safe logging, no next-step dispatch after uncertainty, and no hidden retry.
- [ ] Exercise the admin reward inspection/resolution commands with a real `UNKNOWN` plan. Resolution must be explicit, audited, permission-gated, and must never dispatch/replay a command.
- [ ] Confirm a repeated resolution is rejected after the evidence leaves `UNKNOWN`; also reject invalid plan IDs, indexes, outcomes, missing `confirm`, and incompatible states.
- [ ] Confirm retention does not delete runs with `PENDING`, `IN_PROGRESS`, or `UNKNOWN` plans.
- [ ] Decide who owns reward investigations and record the no-auto-replay policy in the event runbook.

## 13. Gameplay, player state, and anti-abuse

- [ ] Start in Survival and Adventure; reject Creative/Spectator.
- [ ] Reject start with Speed, Jump Boost, Levitation, Slow Falling, Dolphin's Grace, or Wind Charged.
- [ ] Reject or block elytra/glide, active flight, riptide, ender-pearl and chorus/consumable teleports, trident/wind-charge/projectile launch, vehicle/mob mounting, external velocity/knockback, and disallowed effects during a run. Require no invalid landing score.
- [ ] Attempt the same actions repeatedly. Require bounded per-player/per-kind `security.movement_anomaly` records with suppressed counts, no coordinates/item data, and no automatic kick or ban.
- [ ] Mutate allow-flight/flying/gliding/vehicle/riptide/effects/walk speed/movement attributes after event handlers using a disposable later listener. Require the once-per-second authoritative sweep to end the exact run as `MOVEMENT_MODIFIED`.
- [ ] On a disqualified run that already had points, require retained run evidence with persisted score `0`, no Classic/Combo/Flawless projection, no reward intent/dispatch, restored player/world state, `WalkRunEndEvent#score() == 0`, and one audit result containing bounded observed/persisted scores plus `scoring_eligible=false`.
- [ ] Cross the configured arena boundary without falling and perform two physically impossible sub-minimum-interval landings in controlled fixtures. Require fail-closed `MOVEMENT_MODIFIED` cleanup and zero score projection; calibrate the default 150 ms with real latency so ordinary sprint jumps never false-positive.
- [ ] Let mobs/projectiles/explosions/pistons affect the arena/player from outside the region. Require native plugin damage, velocity, collision, block, and landing defenses to hold even when no WorldGuard-style region plugin is installed.
- [ ] Begin with residual horizontal/vertical velocity, non-zero fall distance where safely reproducible, and a legitimate non-default walk speed. Require zero start and post-cleanup velocity, zero post-cleanup fall distance, normal run walk speed, and exact captured restoration of walk speed, health, food, saturation, exhaustion, allow-flight, flying, collision, and pre-run return location. Do not expect the original velocity or fall distance to be restored.
- [x] With an empty inventory and no effects, begin once while idle and once while already sprinting. Both starts passed on Paper 26.2 build 62 during the 2026-07-20 build-013 client pass, locking the player-specific `EntityType.PLAYER` default and exact vanilla `MULTIPLY_SCALAR_1` sprint modifier regression.
- [x] Walk forward continuously through the exact start trigger without pausing. The build-013 client pass activated without `PLAYER_STATE_CHANGED` and without backing into the trigger.
- [x] Repeat by backing into the trigger, sprinting forward, and walking forward while changing yaw/pitch. Five consecutive build-013 starts were recorded with no state refusal. The separate cross-block fail-closed condition remains automated rather than exercised by this client pass.
- [x] During an active run, perform ordinary walking, sprinting, and jumping. The build-013 audit recorded no repeating `external_velocity` anomaly, stalled movement, or false score invalidation; one run scored normally.
- [ ] Apply one non-zero server velocity/knockback with a disposable test source. Require its outgoing vector to be replaced with zero once without event cancellation or a resend loop; require one bounded `external_velocity`/`zeroed` audit observation. Apply a zero vector separately and require it to be ignored.
- [ ] Equip or hold items that alter the movement-speed base or add a custom modifier and require admission denial without inventory mutation. Begin an eligible run, inject a modifier from a disposable plugin, and require one non-rewarding `MOVEMENT_MODIFIED` cleanup within the periodic sweep. Confirm ordinary sprinting remains valid and held-slot changes are denied during the run.
- [ ] Confirm inventories, experience, potion effects, and game mode are not unexpectedly changed by the plugin.
- [ ] While active, attempt outgoing melee and projectile damage, item pickup/drop, inventory click/drag/number-key actions, hand swap, and normal/precise/armor-stand entity interaction. Require every protected interaction to be denied without item loss, duplication, or damage to another entity.
- [ ] Complete jumps at low, medium, and high scores; require reachable bounded platforms and two clear head blocks.
- [ ] Confirm one point per exact target landing and no repeated point while standing/bouncing on the same block.
- [ ] Test ascending, airborne, edge, spoofed support, changed-target, and externally moved-target cases; no invalid point may be awarded.
- [ ] Confirm generated blocks stay inside arena world/build height/border/radius and the configured vertical envelope.
- [ ] With `onlyReplaceAir: true`, confirm random targets never overwrite occupied blocks.
- [ ] Fall, leave, teleport, quit, die, change game mode, time out by total duration, time out by idle duration, admin-stop, reload, and shutdown. Require one cleanup and one terminal run state each.
- [ ] Cancel an active player's external teleport before WalkThePlank's `HIGHEST` decision handler. Require the run and recovery evidence to remain active and unchanged.
- [ ] With a disposable listener ordered after WalkThePlank's decision phase, separately cancel the teleport and rewrite its destination. Require WalkThePlank's `MONITOR` handler to observe the final event state without changing cancellation or destination.
- [ ] Accept an external teleport and require exactly one cleanup on the next tick only when the player, run UUID, attempt UUID, world, and final destination still match. A cancellation, destination mismatch, replaced run, offline player, or failed movement must retain the run/evidence safely.
- [ ] Force scheduler rejection or a second external teleport while one attempt is pending. Require the decision handler to fail closed at `HIGHEST`, no mutation at `MONITOR`, and no duplicate pending attempt, terminal state, persistence, or reward.
- [ ] Repeat accepted/cancelled/modified external teleports with another plugin listening at every priority. Require no “modified event at MONITOR” warning and classify any ordering interaction before event approval.
- [ ] Revoke the configured play permission during an active run. Within the periodic sweep, require one non-rewarding `PERMISSION_REVOKED` cleanup, restored state, and no lingering arena/session.
- [ ] While a run remains active, apply a disallowed effect and external velocity. Require both mutations to be denied without ending or duplicating the run; then leave normally and require one cleanup/terminal state.
- [ ] Confirm damage/hunger cancellation and denied flight/glide/vehicle/velocity/effect behavior while active, with normal behavior restored afterward.
- [ ] Attempt block break/place/interact, bucket use, fluids, sponge, ignite/burn/fade/form/spread, entity block change, explosion, and piston movement across protected-volume boundaries.
- [ ] Confirm unrelated blocks outside every active/journal-protected arena are not globally blocked.
- [ ] Cancel the preferred return teleport from a disposable listener, then the captured-return teleport, then all return attempts. Require ordered live-validated fallback, a bounded failure, a retained durable player-recovery record, and arena quarantine. Remove the cancellation and require `/walk admin recover` or reconnect to restore state, choose a currently safe destination outside every arena, clear the exact record, and free the arena.

## 14. Restoration journal, tiles, conflicts, and recovery

Use a disposable world and keep exact before/after structure or NBT/PDC evidence.

- [ ] Confirm runtime thread names include exactly one `walktheplank-recovery-writer` and one independent `walktheplank-operations-writer`; neither may be an unbounded pool or Paper's shared async scheduler.
- [ ] Hold the first recovery writer alive past both close waits, then attempt a second full plugin/journal open. Require refusal before either journal scans, loads, or deletes a recognizable `.tmp` file. Let the old writer terminate and require the lifetime lock to release automatically before a replacement opens the unchanged evidence. Repeat the delayed-termination ownership check for the operations writer.
- [ ] Block the recovery writer at a reversible test-only boundary while the player traverses a jump. Require responsive server ticks, no Bukkit/Paper async-access warning, no caller-thread file write, and continued operations-worker audit/config progress.
- [ ] Fill the bounded recovery queue in a disposable instrumented profile. Require explicit rejection, a refused/ended affected run, retained exact evidence/quarantine where applicable, no `CallerRunsPolicy` behavior, no world mutation without a durable record, and healthy unrelated arenas.
- [ ] For both restoration and player recovery, force parent-directory fsync to fail immediately after an atomic record rename. Require the durability future to fail uncertain, no player/world mutation, the exact record to remain published and ownership-blocking, and restart to load it when the renamed file remains. Then fail the first exact discard after deletion and require the ticket/lease to remain retained; retry discard, require directory fsync success, record removal, and no leaked restoration byte accounting.
- [ ] Observe a normal multi-jump run with instrumentation: base and target must be durable before start mutation; the successor must become durable and pre-placed during traversal; landing must promote only that exact run/session/platform generation; predecessor deletion and directory fsync must complete before its block lease is released.
- [ ] Delay an old successor completion until after leave/restart/new arena ownership. Require its nonce/generation/arena/block lease revalidation to reject the stale commit without placing a block, releasing a newer lease, or changing the newer run.
- [ ] Land before the pipelined successor is ready. Require no duplicate point and no unsafe async mutation; once durability returns, the same grounded exact-target landing may advance once.
- [ ] During repeated jumps, inspect tick timings and require no journal hash/write/fsync/rename/delete or audit rotation on the primary thread. Paper structure capture, block fingerprint verification, placement, and restoration must remain on the primary thread.
- [ ] Normal run: every retired/generated/start block returns to its exact original state and every `.pending` record is removed only after verification.
- [ ] Use a non-air ordinary start block and confirm exact material/block-data restoration.
- [ ] Use a directional/waterlogged block and confirm complete block data survives.
- [ ] Use a sign with text, color/glow/waxed state as applicable and confirm all sign state survives.
- [ ] Use a container with named items, custom data, lock/name fields as applicable and confirm exact contents/state survives.
- [ ] Use a tile state bearing plugin PersistentDataContainer keys and confirm every key/type/value survives.
- [ ] Confirm the known stateful/workstation material families listed in section 4 cannot be selected as generated platforms, while live `JACK_O_LANTERN` still can. Do not infer that this replaces the separate pre-existing tile-state restoration tests above.
- [ ] Confirm each journal record is written/fsynced/atomically moved before its block changes and contains a valid integrity hash/release identity.
- [ ] Kill the process after journal append but before placement. On restart, require exact already-restored recognition and safe record completion with no world mutation.
- [ ] Kill after placement but before cleanup. On restart, require exact original restoration and arena release.
- [ ] Reconnect the actual player after every kill point. Require retained-run UUID/player/arena verification before automatic recovery, quarantine movement/teleport/damage/inventory/world interactions throughout the lookup, captured health/food/walk-speed/flight/collision restoration, zero velocity/fall distance, and a live-safe return outside every arena. Require the record to remain pending if its world/destination/teleport is unavailable and to clear only after the applicable state/return cleanup succeeds.
- [ ] Reconnect from the death screen and require the next-tick respawn retry to preserve respawn health/hunger/destination while clearing temporary run state. For a terminal accepted external teleport, require state restoration without replaying the captured return. For a terminal normal/custom-exit cleanup whose journal deletion failed, require the configured exit to remain preferred when it is still valid.
- [ ] Copy, tamper, orphan, duplicate, truncate, oversize, and symlink disposable player-recovery records. Require strict schema/integrity/ownership rejection, retained evidence, degraded bounded health, no state application, no raw path/player data in command output, and unaffected-player operation where ownership remains unambiguous. Record that an exact byte-for-byte copy preserving valid UUID/run/arena ownership cannot be distinguished automatically and must be treated as operator-owned evidence.
- [ ] Kill with two active generated blocks and confirm both recover independently and idempotently.
- [ ] Change an expected plugin block to a third state while stopped. Restart/recover must report conflict, preserve the third-party block, retain the record, and quarantine the arena.
- [ ] Unload/remove the world while a record is pending. Require `WORLD_MISSING`, retained record, and quarantine; reload the correct world and recover explicitly.
- [ ] Run `/walk admin recover` repeatedly after successful recovery; subsequent runs must make no additional world change.
- [ ] Race `/walk admin recover` against a pending start whose player/base/target records are durable but not yet activated. Require the authoritative arena lease to exclude every pending-start record from orphan recovery: no record is restored or deleted, no prepared block is mutated, and activation or exact abandonment remains the sole owner.
- [ ] Cancel a start while player/base/target preparations are still queued, then force the first discard submission or post-delete directory fsync to fail. Require the same UUID and arena to remain admission-blocked, exact block leases to remain held, stale durability callbacks to be harmless, periodic/admin recovery to retry, and every lease to release only after all exact discards succeed.
- [ ] Tamper with integrity, filename/ID, fields, structure bytes, format version, size, duplicate block target, or symlink. Require startup to fail safely without silently deleting evidence.
- [ ] Leave a recognizable regular journal pre-commit `.tmp` file and verify startup deletes it durably without treating it as a `.pending` record or changing the world.
- [ ] Leave an unknown-name `.tmp`, symlink, directory, or other non-regular temporary entry and require startup to fail for investigation rather than deleting it.
- [ ] Exercise every journal boundary in synthetic tests: accept the 1,024th pending record, exactly 16 MiB of original structure data, exactly 24 MiB encoded record size, and exactly 256 MiB aggregate pending bytes; reject the first record/byte over each limit before placement. Require no truncation or deletion of evidence.
- [ ] Confirm `/walk admin status` and `/walk debug health` report pending/conflicted/quarantined counts; confirm `/walk admin recover` reports recovered arenas and remaining quarantine. Verify missing-world/conflict detail in the server log and preserved journal evidence rather than expecting every detail in command output.
- [ ] Confirm manual deletion of a journal record is not used as a recovery method in the event runbook.
- [ ] Rehearse the stopped-server unverifiable-evidence runbook on a disposable copy: full plugin/world/player backup first; matching database+journal restore preferred; exact run inspection; authorized manual state/location establishment only when no matching backup exists; move evidence out of the active journal without editing/deleting it; retain the evidence and audit note. Require every arena to remain unavailable when unreadable evidence has no trustworthy arena ownership.

## 15. PlaceholderAPI and public API/events

- [ ] Parse every placeholder documented in `README.md` with a player who has no score, an all-time score, an active run, a queue position/readiness claim, and an active-season score.
- [ ] Include current/max combo, current flawless, saved Combo/Flawless score/rank, category top-ten, and particle/sound/title preference placeholders in that matrix.
- [ ] Confirm no-score/no-run/no-season values are stable `0`, `false`, or empty as documented.
- [ ] Confirm all-time top 1–10 name/score/rank and season top 1–10 match command output, including tied competition ranks.
- [ ] Confirm out-of-range/missing top positions return empty and malformed placeholder parameters do not throw.
- [ ] Confirm offline saved stats resolve by UUID and cannot be claimed by another player with the same name.
- [ ] Confirm capacity/restoration placeholders update after run start/end/quarantine/recovery.
- [ ] Confirm the expansion persists through PlaceholderAPI reload behavior expected by the server and unregisters on plugin disable.
- [ ] Request queue/capacity/player placeholders repeatedly from an asynchronous disposable task while starts, jumps, queue changes, and cleanup occur on the primary thread. Require coherent per-callback values, no concurrent-modification/thread-access warning, and no exception.
- [ ] From a small disposable integration plugin, obtain `WalkThePlankApi` from Bukkit services on the primary thread.
- [ ] Verify release, all-time player record, leaderboard limits 0 and 100, invalid limit rejection, active-run snapshot, capacity snapshot, active-season view, per-player queue view, and recent-run limits 0 and 100 with invalid-limit rejection.
- [ ] Confirm API collections/records cannot mutate plugin internals and the service unregisters on disable.
- [ ] Observe `WalkRunStartEvent`, `WalkJumpEvent`, `WalkRunEndEvent`, `WalkPersonalBestEvent`, and `WalkRewardPlanEvent` on the primary thread.
- [ ] Cancel run start and require an aborted durable start with no active world/session mutation.
- [ ] Cancel reward plan and require no dispatch.
- [ ] Confirm the end event fires after session removal/cleanup and reports reason/duration/score/cleanup accurately, but does not claim the asynchronous completion transaction has committed. Confirm personal-best and reward-plan events occur only after the atomic completion transaction commits.

## 16. Audit, health, shutdown, and crash behavior

- [ ] Run `/walk admin doctor` during active jumps and confirm separate bounded recovery/operations capacity, queued/active, accepted/completed, failed/rejected, and closing/terminated counters without paths, coordinates, names, or record contents.
- [ ] Block audit rotation or an export/config operation and prove recovery journal append/delete latency is unaffected. Reverse the test by blocking recovery durability and prove prepared audit records still drain on the operations writer.
- [ ] Reload with active and just-ended sessions. Require main-thread world/player cleanup, an off-thread recovery barrier, a return to the primary thread for quarantine/lease checks, final disk revalidation, generation-CAS publication, and no tick-thread fsync.
- [ ] Race an external `config.yml` edit before commit, during cleanup, and during provisional arena-edit activation. Require stale publication rejection or restoration of the prior runtime without overwriting the newer external file.
- [ ] Tamper with `config.yml.backup` after a disposable arena edit commits but before rollback. Require rollback to use the exact token-bound original bytes, not the tampered shared backup.
- [ ] Disable while an arena edit is captured, queued, durably committed, and provisionally activated in separate runs. Require the FIFO shutdown reconciliation barrier to leave either a fully verified activation or the exact prior file; no committed-but-unactivated edit may escape.
- [ ] Confirm `audit/audit.jsonl` is valid one-object-per-line JSON with timestamp, release, event, and only bounded safe fields.
- [ ] Require every new record to include one chain UUID, increasing sequence, previous hash, and 64-hex SHA-256 record hash. Stop/restart across rotations and require doctor to report verified healthy continuity.
- [ ] On stopped disposable copies, modify one same-length retained record, delete/truncate the current tail, break a previous hash, alter state, and alter the retention anchor. Require fail-closed startup in every case without silently resetting or deleting evidence.
- [ ] Start once with a valid legacy unchained `audit.jsonl`. Require it to be preserved as an explicit `audit-legacy-*.jsonl`, a new chain at sequence 1, and no claim that the legacy segment was verified.
- [ ] Force more than ten chain archives. Require sequence-ordered retention, an advanced durable anchor, successful restart verification, and no pruning of a legacy archive merely because it shares the audit directory.
- [ ] Hard-kill archive pruning once after the staged rename but before the anchor commit, and once after the anchor commit but before staged-file deletion. Require restart to restore the archive in the first case, finish deletion in the second, verify the complete retained chain, and leave no `.pruning` file.
- [ ] Delete only `audit-state.properties`, then delete both checkpoint sidecars while retained chained data exists. Both cases must fail startup rather than reclassify the chain as legacy. Separately interrupt an empty first startup after its zero-sequence anchor commit and require deterministic state-checkpoint recovery.
- [ ] Exercise plugin, run, queue, reward/staff reward, season-transition, leaderboard-export, arena-edit success/failure/rollback, startup restoration, and operator-triggered recovery events; compare audit order/IDs to database records.
- [ ] Confirm player-run arena edits, `/walk admin recover`, queue pause/resume/drain, admin stop/open, reload, and validate carry the correct operator UUID and `player` actor category. Repeat applicable commands from console and require an explicit `system` actor category with no fabricated player UUID; verify safe target/result/fingerprint fields.
- [ ] Exercise doctor start, pass, warning, and SQLite-probe-failure paths. Require bounded `admin.doctor` audit outcomes with actor attribution and aggregate status only—never report contents, paths, SQL, commands, credentials, or exception messages.
- [ ] Confirm no raw command, credential, chat, inventory content, IP, or unnecessary path appears in audit or copy/paste-safe health.
- [ ] Force audit rotation on a disposable low-limit fixture or dedicated test and confirm unique archives and retention ordering.
- [ ] Make audit writes fail. Require one clear server-log error and degraded health/metrics without recursive logging or gameplay-state corruption.
- [ ] Separately make the audit directory unsafe or unwritable before startup. Require fail-closed initialization rather than a partially enabled plugin without its audit stream.
- [ ] Hold a SQLite write lock through and beyond the configured timeout; confirm bounded retry/failure behavior, accurate pending/last-failure health, and no main-thread stall.
- [ ] Test read-only/full-disk-like failures where practical on a disposable profile; require no empty replacement database and safe disable/failure.
- [ ] Run doctor with a known WAL file and known migration-backup inventory; compare byte counts and backup count with stopped-server filesystem evidence. The report must not expose any filename or path.
- [ ] Invoke doctor repeatedly and concurrently with leaderboard reads/writes. Require bounded repository queue/task health, responsive server ticks, completed futures, and no unbounded probe/thread creation.
- [ ] Invoke doctor immediately before reload/disable and force a probe failure on a disposable database. Require a bounded privacy-safe result or contained cancellation, with no raw exception, rejected-task leak, shutdown hang, repair attempt, or database replacement.
- [ ] Start and stop with no players, active runs, pending starts, queued/ready players, and quarantined records.
- [ ] Clean shutdown must close menus, cancel starts, drain queue, restore sessions, retry quarantine, unregister API/PlaceholderAPI, and drain accepted database work for up to 15 seconds.
- [ ] Require `WalkThePlank disabled; arena blocks and player state were restored` and no pending recovery/operations/database-write timeout in both build-016 controlled/console-smoke profiles.
- [ ] End an ordinary zero-score run by falling. If asynchronous cleanup briefly retains the arena, require only the bounded informational reservation/settled messages—never a false `SEVERE` quarantine message—and require the arena to become available once.
- [x] Start and end at least five rapid consecutive runs in the same arena. Six build-014 client runs completed on 2026-07-20 with only the expected reservation/settled information, no lifecycle failure, no quarantine, and no blocked next run. `BlockLeaseRegistryTest` separately locks the delayed stale-completion case and proves that it preserves a newer exact owner without world mutation or duplicate release.
- [ ] Repeat the rapid-run cleanup rehearsal with at least one scored run against the final frozen candidate.
- [ ] Treat `disabled with cleanup errors`, any remaining quarantine/pending restoration, or an unclassified database failure as no-go.
- [ ] Force repository drain timeout/interruption in a synthetic test and confirm every accepted future reaches success or exceptional completion; no command caller may hang forever.
- [ ] Confirm queued operations are rejected and settled deterministically on close timeout. Separately document that a SQLite JDBC call already running may outlive an unsuccessful close if interruption is ignored; treat that log/return path as no-go and never start a second process on the database until the first process is gone.
- [ ] On a disposable process, force an unsuccessful repository close and confirm a competing live process still cannot acquire the instance lock. After proving the original process has exited, require the operating system to release ownership and the replacement to acquire the existing regular lock file without deleting it.
- [ ] Kill the JVM/host instead of clean shutdown during active run, queued readiness, run persistence, reward stages, and journal stages; verify the exact recovery rules in the preceding sections.

## 17. Test-server synchronization and regression pass

- [ ] Run `./gradlew syncTestServer` only after source/build/migration gates pass.
- [ ] Confirm build 016 is active and all older WalkThePlank/InfinityParkour JARs—including both `TEST-ONLY-` artifacts—are disabled/absent.
- [ ] Confirm copied live data belongs to the test server, not `_resources`.
- [ ] Restart the synchronized server from a clean stop and repeat startup/info/validate/health/doctor/top/GUI/gameplay/queue/season/export/reward/recovery/disable smoke.
- [ ] Search the full `latest.log` for `InfinityParkour`, `WalkThePlank`, `WARN`, `ERROR`, `SEVERE`, `Exception`, `deprecated`, and task rejection; classify every match.
- [ ] Re-run `PRAGMA quick_check` and the 100-row/top-ten comparison after build-016 console smoke: 100 UUID rows, score sum 4256, maximum 149, unchanged top ten, `quick_check=ok`.
- [ ] Re-hash the staged JAR and compare it with the candidate record.

## 18. Rollback rehearsal

- [ ] Stop Paper and preserve post-test DB, journal, audit, exports, and logs separately.
- [ ] Restore the exact previous JAR and matched pre-deployment data folder together.
- [ ] Start the previous supported environment and verify the all-time leaderboard/top ten.
- [ ] Confirm the production runbook warns that database rollback discards later event scores and never reconciles by username.
- [ ] Confirm pending build-016 restoration/player-recovery records are resolved or preserved before starting an older plugin that cannot read them.
- [ ] Record rollback duration, responsible operator, file locations, and communication plan.

## 19. Go/no-go sign-off

- [ ] Freeze configuration, translations, database baseline, external reward content, and the checksummed JAR after rehearsal.
- [ ] Take a fresh stopped-server production backup.
- [ ] Assign one operator to monitor console, health, pending writes, queue, quarantine, season state, and reward uncertainty during the event.
- [ ] Assign a separate owner for reward investigation and rollback.
- [ ] Record every accepted known limitation, especially Paper beta status, external-command non-transactionality, no automatic reward replay, and the requirement for retained-run ownership plus a live-safe destination during hard-kill player recovery.
- [ ] Require explicit go/no-go approval. Blank or incomplete evidence means **NO-GO**.

| Sign-off | Value |
| --- | --- |
| Decision | **PENDING / NO-GO until completed** |
| Release operator | |
| Independent reviewer | |
| Date/time | |
| Git commit | |
| Artifact SHA-256 | |
| Full stopped-server backup | |
| Automatic schema-v3 backup | |
| Config fingerprint | |
| Reward-risk owner | |
| Rollback owner | |
| Accepted limitations | |
