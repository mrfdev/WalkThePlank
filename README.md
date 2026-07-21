# WalkThePlank

WalkThePlank is an endless, procedurally generated parkour minigame for Paper. A player starts in a configured arena, jumps to the next generated platform, and earns one point for every successful landing. Runs continue until the player falls, leaves, times out, disconnects, or is stopped. Personal bests, ranks, event-season scores, run records, and reward-dispatch state are stored in a local SQLite database.

This project is the modern continuation of [Meldiron's InfinityParkour](https://github.com/Meldiron/InfinityParkour), including the historical fixes used by [The456gamer's fork](https://github.com/The456gamerForks/InfinityParkour). The modern code is maintained at [mrfdev/WalkThePlank](https://github.com/mrfdev/WalkThePlank) as a custom 1MB build for Java 25 and Paper 26.2.

The supplied historical binaries were also compared with the tracked source.
The result is recorded in [legacy-artifact-audit.md](legacy-artifact-audit.md):
the 1.16.4 patched JAR contains only the known descending-scoreboard query fix,
which the modern implementation preserves, while the recovered 2.0.0 JARs are
intermediate builds from this modernization and are fully superseded.

The Bukkit plugin name intentionally remains `InfinityParkour`. This preserves the existing data directory at `plugins/InfinityParkour/`, so the live `config.yml`, `translations.yml`, and `database.db` can be upgraded in place without renaming player data.

Current source release: **v2.7.0, build 024**

Expected standalone artifact:

```text
1MB-WalkThePlank-v2.7.0-024-j25-26.2.jar
```

> Real-player testing through build 022 confirmed normal movement, sequential scoring, exact two-platform rotation, cleanup, the modern GUI, and the summer appearance. Build 023 made FIFO reservation a separate default-off permission. Build 024 adds the exact eight-event theme roster, six per-theme sound cues, optional safe CMI sound delivery, and the fail-closed `event.enabled` participation switch. Prior machine evidence remains historical, so build 024 needs its own freeze, Paper smoke, synchronization, sound review, and client approval.

See [CHANGELOG.md](CHANGELOG.md) for release changes and [feature-improvements-walktheplank.md](feature-improvements-walktheplank.md) for the authoritative future-development TODO and implemented-versus-remaining status.

## Features

- Endless generated parkour with bounded, reachable jump planning and two clear blocks of headroom above every candidate platform.
- Multiple independently configured arenas, one active runner per arena, and `RANDOM`, `ROUND_ROBIN`, `LEAST_RECENTLY_USED`, or `PINNED` arena selection.
- A fair FIFO queue with explicit join/leave/status/ready actions, readiness expiry, reminders, a join cooldown, and staff pause/resume/drain controls.
- Maximum-run and no-progress timeouts so abandoned sessions cannot permanently consume an arena.
- Configurable start and optional exit positions, platform palette, horizontal radius, fall distance, particles, queue timing, rewards, and permissions. The exact bundled event roster is `default`, `valentine`, `easter`, `summer`, `halloween`, `thanksgiving`, `christmas`, and `anniversary`; each atomically selects a safe block palette, particle, and six sound cues. `custom` remains a compatibility mode for historical top-level values, not another event preset.
- A fail-closed `event.enabled` switch keeps diagnostics, configuration, statistics, and leaderboards loaded while refusing new runs and queue reservations outside an event. No schedule or automatic activation is included.
- Player-state capture and restoration for return location, health, food, saturation, exhaustion, walk speed, flight, and collision state; arena admission clears residual velocity and applies the normal walk speed for the run, and controlled cleanup leaves velocity and fall distance at zero rather than restoring their pre-run values.
- Cleanup for normal leaves, falls, teleports, timeouts, quits, deaths, game-mode changes, reloads, errors, and shutdown.
- External active-run teleports are decided at `HIGHEST`; `MONITOR` only observes the final cancellation/destination state, and cleanup commits only after a next-tick run/attempt/world/destination verification.
- A write-ahead restoration pipeline that captures Paper state on the primary thread, persists immutable bytes on a bounded single recovery writer, and returns to the primary thread for exact run/arena/block-fingerprint revalidation before mutation. Startup recovery restores only an expected plugin block, recognizes an already restored exact snapshot, and quarantines conflicts or missing worlds instead of overwriting them blindly.
- A pipelined successor begins durable preparation while the player traverses the current jump, but remains hidden after durability completes. A confirmed landing consumes that exact preparation on the primary thread: the departed platform is restored and one new destination is placed, preserving exactly two visible platforms. An early landing waits for the same exact completion without asynchronous world mutation. Journal delete and directory fsync complete off-thread before the retired block's exact lease is released.
- Paper one-block structure snapshots for original block data and block-entity contents. Known stateful/workstation material families—including furnaces, barrels, chests, copper-chest variants, shelves, and note blocks—are denied as generated platforms without using Paper's deprecated interactable test. Restoration of a pre-existing tile/container/sign/PDC block remains a required beta acceptance test.
- Active-arena protection against interaction, breaking, placement, buckets, fluids, sponges, fire, block transforms, explosions, pistons, and entity block changes.
- Movement hardening while playing: damage and hunger are cancelled; flight, elytra/gliding, riptide, ender-pearl/consumable teleports, projectile launch, vehicles/mounts, configured potion-effect advantages, and non-vanilla movement-speed/walk-speed state are blocked or disqualify altered runs. Non-zero server-applied velocity is replaced with zero without cancelling Paper's cause-less velocity event, preventing knockback assistance and cancellation loops. Arena bounds and a conservative minimum inter-jump interval are checked server-side. Eligibility is rechecked before activation and every second; anomalies are rate-limited into the audit and never cause an automatic ban.
- Active-run interaction isolation: outgoing player/projectile damage, item pickup/drop, inventory click/drag, hand swaps, held-slot changes, and entity interaction are denied while the run is active.
- Server-side landing checks for ground support, finite non-ascending vertical velocity, and the exact intact target block.
- A typed-holder 54-slot GUI with a readable near-black title, the standard 1MB light-blue outer frame, an open center, centered tutorial/play/statistics actions, a bottom-left viewer head with live personal stats, and adjacent bottom-right `/menu`/close controls. Item names and every nested lore component are forced non-italic and use the shared readable 1MB pastel palette. The exact historical live GUI text upgrades to these bundled defaults in memory, while genuinely customized operator text is preserved. Click/drag cancellation, a bounded menu-open cooldown, owner UUID, random nonce, monotonic generation, exact inventory identity, duplicate suppression, one pending action, and execution-time permission/state validation harden every menu session.
- UUID-owned all-time personal bests with competition ranking, last-known-name refresh, and a live top ten.
- Separate Combo and Flawless personal-best categories. Combo is the longest configured time-target streak; Flawless records the final Classic score only when every transition meets that target. Neither category changes or multiplies Classic.
- Theme-specific start, landing, milestone, combo, finish, and failure sounds. Each cue independently supports direct Paper playback or the optional CMI sound command, enablement, 0–100% volume, and 0.5–2.0 pitch; defaults use 80% volume and vanilla pitch. Milestone cues take precedence over combo cues, combo cues occur on non-milestone multiples of five, and sounds never override a player's UUID-owned sound preference.
- Configurable native milestones using Adventure action bars/titles and Paper particles, with UUID-owned full/reduced/off particles and independent sound/title preferences through `/walk settings`.
- Explicit event seasons with planned, active, closed, and archived lifecycle states. Starting a run captures the active season, so closing a season does not silently redirect an already-started result.
- Durable run history containing run UUID, player UUID, last-known name, arena, start/end, score or interruption state, end reason, release, season, and reward-plan association. The newest 10,000 prunable terminal rows are retained in addition to every active or unresolved row.
- Permission-separated staff run investigation by exact UUID, status, arena, or season, with bounded redacted output and linked reward-plan state.
- A reward execution ledger with deterministic plan IDs, idempotency keys, command roots and SHA-256 hashes, atomic run-completion/reward-intent persistence, per-step dispatch states, a per-player completion barrier, and startup freezing of uncertain work without automatic replay.
- A strict 1–32-entry reward command-root allow-list checked during configuration validation and again at runtime before command-map lookup or dispatch. The bundled roots are `say`, `cmi`, `uf`, and `welcomes`; operators should remove providers they do not intentionally use.
- Atomic UUID-only CSV and JSON exports for all-time, active-season, and historical-season leaderboards. CSV fields receive spreadsheet-formula neutralization as defense in depth.
- Schema-v3 SQLite migration with a verified pre-migration backup, bounded verified automatic-backup retention, schema/index/foreign-key validation, preservation of existing UUID-linked Classic rows, and exclusive process ownership of the configured database.
- A built-in PlaceholderAPI expansion for all-time, season, queue, arena, and active-run values, backed by one immutable game-state publication per callback so asynchronous requests never traverse Bukkit-thread mutable collections.
- A read-only Bukkit services API plus primary-thread lifecycle events.
- Permission-filtered help, build information, safe diagnostics, health counters, configuration validation, and guarded in-game arena editing.
- A native Paper lifecycle-registered Brigadier command tree with typed online-player, UUID, bounded-integer, enum-literal, arena-ID, season-name, and explicit-confirmation arguments. Command behavior is separated into player, queue, arena, season, reward, investigation, and database modules; `plugin.yml` remains the plugin descriptor but no longer owns legacy command execution or tab completion.
- Trusted translation formatting is parsed before dynamic values are inserted as literal Adventure components. Existing unmarked ampersand templates remain supported, while an explicit `minimessage:` prefix opts a trusted template into MiniMessage.
- `/walk admin doctor` creates a bounded privacy-safe support report, schedules its read-only SQLite integrity/storage probe off the primary server thread, and reports both bounded I/O workers without paths or record contents.
- A compact rotating SHA-256-chained JSONL audit stream for plugin, run, queue, security-anomaly, and reward lifecycle events. Restart verifies every retained record against durable state and a retention anchor; existing unchained logs are preserved as explicitly named legacy archives. Prepared immutable records are written by a separate bounded operations worker, so audit rotation, exports, or configuration commits cannot delay recovery journals. Raw reward commands and arbitrary nested data are excluded.
- SQLite-only standalone packaging. The shaded JAR includes SQLite JDBC and intentionally includes no MySQL/MariaDB connector, Paper classes, PlaceholderAPI classes, or live `.db` data.
- Strict Java compilation with `-Xlint:all -Werror`; startup configuration, database, journal, or identity failures disable the plugin instead of deliberately continuing with partial state. A partially completed enable is explicitly reported as startup-aborted and never claims clean restoration.

## Requirements and dependencies

- Paper **26.2 build 62 beta or newer**. `plugin.yml` rejects pre-26.2 servers and startup additionally fails closed when Paper's reported build is below 62 or unavailable.
- Java **25** for both building and running. Compilation uses `--release 25`.
- PlaceholderAPI is optional. The compile target is **2.12.3**; without it, only placeholders are unavailable.
- SQLite requires no separate service. SQLite JDBC **3.53.2.0** is shaded into the standalone artifact.
- The checked-in wrapper uses Gradle **9.6.1**, the build uses Shadow **9.5.1**, and automated tests use JUnit **6.1.2**.

The plugin declares `api-version: 26.2`. It is not intended to load on Paper/Minecraft 1.20.x, 1.21.x, or 26.1.x.

PlaceholderAPI is the only optional linked Java/API integration. `plugin.yml` also soft-depends on `CMI`, `UltimateFireworks`, and `PyroWelcomesPro` as startup-order hints because enabled reward-root validation is synchronous and the live configuration may call their console commands. CMI can additionally deliver a deliberately configured theme cue through its public `/cmi sound` command; this bridge validates the sound token plus volume/pitch and never links to CMI or CMILib classes. WalkThePlank does not shade those providers and does not require CMI while all sound cues use `provider: default` and rewards do not call it. `CMILib`, `Vault`, and `PyroLib` are neither WalkThePlank dependencies nor soft dependencies. The hooks diagnostic reports presence for operator convenience. Every configured reward command root must also be explicitly present in `rewards.allowedCommandRoots`, and every external reward or CMI sound still needs a cold-start and in-game rehearsal; “plugin present” is not proof that external content behaves correctly. The newest inspected local Paper 26.2 compatibility profile has CMI **9.8.8.5** and CMILib **1.5.9.9**; record the versions actually installed in the final WalkThePlank staging profile.

The plugin does not read CMI's database and does not contact an online profile API. The supplied live leaderboard already contains UUIDs for its 100 rows, so no CMI lookup is needed for that data. A genuinely username-only legacy row remains visible but unresolved until an explicit offline migration process assigns a verified UUID.

## Standalone JAR and release record

Build the deployment artifact with the checked-in Gradle wrapper:

```bash
./gradlew clean freezeCandidate
```

The deployable file is:

```text
build/libs/1MB-WalkThePlank-v2.7.0-024-j25-26.2.jar
```

Do not deploy the `-unshaded.jar`; it does not contain SQLite JDBC. `build` runs the automated tests, creates the shaded artifact, and executes the archive/metadata verification gate. `freezeCandidate` additionally rejects a dirty Git tree. Every candidate embeds the full source commit and strict dirty state in `build-info.properties` and the JAR manifest; `/walk info`, `/walk debug overview`, and startup show the abbreviated source label.

Earlier completed machine evidence is preserved in annotated RC tags and ignored operator release archives, but it does not prove build 024. The exact clean build-024 candidate needs two byte-identical builds, scenario-profile evidence, Paper smoke, live-copy preservation checks, checksum, archive, and candidate tag; none of that final evidence may be copied from another artifact. The checksum is deliberately not committed back into this source tree because changing a committed checksum would create a new source commit and invalidate the commit embedded in the JAR.

| Release property | Value |
| --- | --- |
| Filename | `1MB-WalkThePlank-v2.7.0-024-j25-26.2.jar` |
| Source identity | Full Git commit plus strict clean/dirty state embedded in the JAR |
| Final size and SHA-256 | Annotated candidate tag and operator release archive |
| Automated test result | Must pass with zero failures/errors/skips and strict Java 25 compilation |
| Archive verification | Must pass twice with byte-identical clean-commit outputs |
| Paper 26.2 smoke | Must pass startup/reload/shutdown on the exact final JAR |
| Test-server synchronization | Must leave the exact final JAR as the only active InfinityParkour/WalkThePlank build |

The in-game multiplayer, movement, real-client GUI abuse, hard-kill recovery, reward-provider, and rollback portions of the beta checklist still require a human tester before event approval. An RC tag records automated freeze evidence only; it is not production approval.

Useful build commands:

```bash
./gradlew releaseInfo
./gradlew test
./gradlew shadowJar
./gradlew verifyReleaseJar
./gradlew freezeCandidate
./gradlew syncTestServer
```

`releaseInfo` must print version `2.7.0`, build `024`, and the exact filename above. `syncTestServer` is a local deployment helper: it builds the release, moves older WalkThePlank/InfinityParkour JARs from the bundled Paper test server into `plugins-disabled/walktheplank/`, and copies only build 024 into the active plugin directory.

## Install or upgrade

> Stop Paper and take an operator-controlled backup before the first build-020 start. The automatic SQLite migration backup is an additional safeguard, not a substitute for a full server/data backup.

For an existing server:

1. Rehearse the entire update against a disposable copy of the live server and data.
2. Stop Paper completely.
3. Back up the old plugin JAR and the complete `plugins/InfinityParkour/` directory as one matched rollback set.
4. Keep the data directory named `plugins/InfinityParkour/`.
5. Remove or disable every older InfinityParkour/WalkThePlank JAR. Paper must see only one plugin with the `InfinityParkour` name.
6. Copy `1MB-WalkThePlank-v2.7.0-024-j25-26.2.jar` into `plugins/`.
7. Start Paper and inspect the complete startup log. A successful live-data start should report 100 preserved Classic scores and, on the first schema-v3 migration only, a verified pre-migration backup path.
8. Run `/walk info`, `/walk admin validate`, `/walk admin status`, `/walk admin doctor`, `/walk debug all`, and the full beta checklist before allowing players in.
9. Stop Paper cleanly once and require the clean restoration/disable message before the event rehearsal is accepted.

For a fresh installation, install the shaded JAR, start and stop Paper once, then edit `plugins/InfinityParkour/config.yml` and `translations.yml` while the server is stopped.

### Live reference files

`_resources/InfinityParkour/` contains reference/live-server data and is intentionally ignored by Git. `servers/` is also ignored. Neither directory nor any `.db` file is embedded in the release artifact.

For staging, copy the **contents** of the reference folder into the staging server's `plugins/InfinityParkour/` directory. Never run migration tests directly against `_resources/`; hash the source file, work on a disposable copy, and prove that the source hash is unchanged afterward.

Do not create a fresh plugin data directory over the live folder. That would discard the event arena, translations, reward rules, and active leaderboard.

## Commands

The primary command is `/walktheplank`. `/walk`, `/infinityparkour`, and `/infp` are equivalent aliases. Paper registers the typed Brigadier tree through `JavaPlugin#getLifecycleManager` and `LifecycleEvents.COMMANDS`; no `paper-plugin.yml` is used. Help, client-side syntax, and suggestions are permission-filtered. Online players use Paper's player argument, IDs use Paper's UUID argument, numeric limits are bounded by Brigadier, and destructive reward/arena decisions retain literal `confirm` nodes.

### Player commands

| Command | Permission | Behavior |
| --- | --- | --- |
| `/walk` | `infinityparkour.opengui` | Opens the main menu. Console receives help instead. |
| `/walk play` | `infinityparkour.play` | Starts a run when an arena is free. Without queue permission, an occupied arena produces a wait-and-retry message and never enrolls the player. |
| `/walk queue [status]` | `infinityparkour.play` | Shows queue state; `status` is the default action. |
| `/walk queue join` | `infinityparkour.queue.join` | Joins the optional FIFO queue, subject to eligibility and cooldown. Hidden and denied without the explicit default-off permission. |
| `/walk queue leave` | `infinityparkour.play` | Leaves the queue and starts the configured rejoin cooldown. |
| `/walk queue ready` | `infinityparkour.queue.join` | Accepts an active readiness assignment and starts the reserved run. Permission revocation removes the reservation. |
| `/walk leave` | `infinityparkour.leavearena` | Ends the active run safely. An active player can still leave if this permission is revoked mid-run. |
| `/walk stats [classic]` | `infinityparkour.statscmd` | Shows UUID-linked Classic personal best, rank, population, and top percentage. |
| `/walk stats combo` | `infinityparkour.statscmd` | Shows the longest within-target jump streak and category rank. |
| `/walk stats flawless` | `infinityparkour.statscmd` | Shows the best run in which every transition met the configured combo target. |
| `/walk top [all-time]` | `infinityparkour.topcmd` | Shows the all-time top ten. |
| `/walk top season` | `infinityparkour.topcmd` | Shows the active-season top ten, or reports that no season is active. |
| `/walk top combo` | `infinityparkour.topcmd` | Shows the separate Combo top ten. |
| `/walk top flawless` | `infinityparkour.topcmd` | Shows the separate Flawless top ten. |
| `/walk settings` | `infinityparkour.preferences` | Shows personal particle, sound, and title preferences. |
| `/walk settings particles <full\|reduced\|off>` | `infinityparkour.preferences` | Persists WalkThePlank-owned particle density for this UUID. |
| `/walk settings sounds <on\|off>` | `infinityparkour.preferences` | Enables or disables milestone sounds for this UUID. |
| `/walk settings titles <on\|off>` | `infinityparkour.preferences` | Enables or disables milestone titles for this UUID; action-bar feedback remains concise. |
| `/walk info` | `infinityparkour.info` | Shows release, artifact, target platform, score count, arenas, and docs URL. |
| `/walk help` | `infinityparkour.help` | Shows only commands the sender can use. |

### Administrative commands

`infinityparkour.admin` grants all administrative children. Individual duties can instead receive only the relevant leaf permission.

| Command | Permission | Behavior |
| --- | --- | --- |
| `/walk admin [help]` | Any admin child | Shows permission-filtered administrative help. |
| `/walk admin open <player>` | `infinityparkour.admin.open` | Opens a new plugin menu for an exact-name online player. |
| `/walk admin reload` | `infinityparkour.reload` | Validates/reloads config and translations, closes menus, ends active runs, cancels pending starts, and drains the queue. Database-path changes require a restart. |
| `/walk admin stop <player>` | `infinityparkour.admin.stop` | Stops an online player's run without reward eligibility. The run and positive score are still persisted. |
| `/walk admin recover` | `infinityparkour.admin.recover` | Retries pending/quarantined block restoration and starts ownership-verified recovery lookups for eligible online players with durable pending records, without overwriting conflicts. |
| `/walk admin validate` | `infinityparkour.admin.validate` | Validates on-disk config/translations without applying them; reports errors, warnings, and the safe SHA-256 config fingerprint. |
| `/walk admin run` | `infinityparkour.admin.investigate` | Queries bounded, redacted retained-run evidence without name-based ownership. |
| `/walk admin status [player]` | `infinityparkour.admin.debug` | Shows global health or an online player's active-run status. |
| `/walk admin doctor` | `infinityparkour.admin.debug` | Starts an asynchronous read-only SQLite probe and prints a bounded privacy-safe support report covering source provenance, targets/runtime, hooks/command roots, storage health, journals/quarantine, uncertain rewards, and queue/task health. |
| `/walk admin debug [page]` | `infinityparkour.admin.debug` | Shows a safe diagnostic page. |
| `/walk debug [page]` | `infinityparkour.admin.debug` | Root-level shortcut for the same diagnostics. |

Debug pages are `overview`, `health`, `hooks`, `commands`, `permissions`, `placeholders`, `config`, and `all`.

### Arena editor

All arena mutations require `infinityparkour.admin.arena`. Location-taking commands are player-only. Arena IDs contain 1–32 characters, start with a lowercase letter or digit, and otherwise use only lowercase `a-z`, digits, `_`, or `-`. Edits require a fully idle game: no queue entries, active or pending run, teleport/recovery lookup or completion, quarantined arena, recovery record, or arena/block lease may remain. A candidate is validated before an atomic file replacement; the previous bytes are kept in `config.yml.backup`, and a failed activation attempts an automatic rollback.

| Command | Behavior |
| --- | --- |
| `/walk admin arena list` | Lists IDs, worlds, starts, and custom-exit state. |
| `/walk admin arena create <id>` | Creates an arena at the executing player's block. |
| `/walk admin arena setstart <id>` | Replaces the arena's block-aligned start with the player's location. |
| `/walk admin arena setexit <id>` | Captures the player's precise location as the custom exit. |
| `/walk admin arena clear-exit <id>` | Returns future runners to their captured pre-run location. |
| `/walk admin arena validate [id]` | Validates the complete layout without changing it. An optional ID first confirms that arena exists. |
| `/walk admin arena remove <id> confirm` | Removes an arena only with the literal confirmation word. |

### Queue administration

These commands require `infinityparkour.admin.queue`:

| Command | Behavior |
| --- | --- |
| `/walk admin queue [status]` | Shows enabled/paused state and waiting/ready totals. |
| `/walk admin queue pause` | Preserves positions, freezes existing readiness deadlines, blocks readiness consumption, and stops new assignments until resume. |
| `/walk admin queue resume` | Resumes readiness assignment. |
| `/walk admin queue drain` | Removes every queued/ready player and informs online players. |

### Season administration

These commands require `infinityparkour.admin.season`. Season IDs are UUIDs displayed by `list`.

| Command | Behavior |
| --- | --- |
| `/walk admin season list` | Lists newest-created seasons and lifecycle state. |
| `/walk admin season create <name>` | Creates a unique planned season; names may contain spaces and are 1–80 characters. |
| `/walk admin season activate <uuid>` | Activates a planned season when no other season is active. |
| `/walk admin season close <uuid>` | Closes a planned or active season. |
| `/walk admin season reopen <uuid>` | Returns a closed season to planned; activation remains separate. |
| `/walk admin season archive <uuid>` | Permanently archives a closed season. |

There is no implicit leaderboard reset. All-time scores remain intact. At most one season is active, and an archived season cannot be reopened.

### Leaderboard export

These commands require `infinityparkour.admin.export`:

| Command | Behavior |
| --- | --- |
| `/walk admin export all-time` | Writes all-time CSV and JSON snapshots. |
| `/walk admin export current-season` | Writes the active-season snapshots. |
| `/walk admin export season <uuid>` | Writes a historical season's snapshots. |

Exports are written atomically beneath `plugins/InfinityParkour/exports/`. Each row contains `uuid`, `last_known_name`, `score`, `rank`, and `updated_at`. The whole export fails safely if any requested row lacks a UUID; it never falls back to username ownership. CSV cells beginning with spreadsheet formula markers are prefixed with an apostrophe as defense in depth, while JSON preserves the validated raw display value.

### Reward inspection and resolution

These commands require `infinityparkour.admin.reward`. They expose only persisted IDs, command roots, hashes, and outcomes; they never show or reconstruct raw command arguments.

| Command | Behavior |
| --- | --- |
| `/walk admin reward` or `/walk admin reward list [status] [limit]` | Lists retained plans newest first. The default is `UNKNOWN` with a limit of 20; valid limits are 1–100. Valid statuses are `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `PARTIAL`, `UNKNOWN`, and `ABANDONED`. |
| `/walk admin reward inspect <plan-uuid>` | Shows the run UUID, plan status, creation time, and zero-based step indexes with redacted command root/hash evidence. |
| `/walk admin reward resolve <plan-uuid> <step> <succeeded\|failed\|skipped> confirm` | Records a staff conclusion for one `UNKNOWN` step. It accepts only an unresolved `UNKNOWN` plan/step, derives the new plan status, writes an audit record, and never dispatches a command. |
| `/walk admin reward abandon <plan-uuid> confirm` | After `reward list pending` and inspection, durably changes the inspected plan's remaining `PENDING` steps to `SKIPPED`. It is the safe reconciliation route for a plan left wholly `PENDING` by a restart and never replays a command. If invoked during a transient `DISPATCHING` state, uncertainty is preserved as `UNKNOWN` rather than guessed. |

`succeeded`, `failed`, and `skipped` are operator assertions based on downstream evidence; WalkThePlank cannot infer an external plugin's economic state after a crash. Inspect the plan and downstream system first. A repeated or state-incompatible resolution is rejected rather than silently rewriting evidence. To reconcile a pre-dispatch crash, use `reward list pending`, inspect the plan, then explicitly abandon it. There is intentionally no in-game retry/resume command because raw commands and personal-best eligibility are not retained in the ledger.

### Retained-run investigation

These read-only commands require `infinityparkour.admin.investigate`. They query the retained SQLite history asynchronously using prepared, exact filters and return at most 100 newest-first rows. Output omits player names, raw reward commands, and filesystem paths. Every successful query, including an empty result, records the operator UUID when available, a bounded safe filter description, and result count in the audit stream.

| Command | Behavior |
| --- | --- |
| `/walk admin run` | Shows permission-filtered investigation help. |
| `/walk admin run list [status\|all] [limit]` | Lists newest retained runs. The default is `all`/20; limits are 1–100 and statuses are `STARTED`, `COMPLETED`, `ABORTED`, or `UNKNOWN`. |
| `/walk admin run inspect <run-uuid>` | Loads one exact run UUID and shows player UUID, status, arena, timestamps, score/reason, release, season UUID, and linked reward plan ID/status when present. |
| `/walk admin run player <player-uuid> [limit]` | Filters by immutable player UUID; names are not accepted as an ownership lookup. |
| `/walk admin run arena <arena-id> [limit]` | Filters by exact retained arena ID. |
| `/walk admin run season <season-uuid> [limit]` | Filters by exact captured season UUID. |

The internal repository query also supports exact release and a complete half-open start-time interval no wider than 366 days. Those filters are intentionally not exposed as free-form in-game syntax in this build; a future export/API design must retain the same 100-row and redaction limits.

### Compatibility forms and examples

| Compatibility form | Equivalent |
| --- | --- |
| `/walk open <player>` | `/walk admin open <player>` |
| `/walk reload` | `/walk admin reload` |
| `/walk version` | `/walk info` |

Console may also use `/walk <online-player>` as an exact-name, permission-checked menu-open convenience.

FIFO reservation is optional and default-off. Ordinary players do not receive
`infinityparkour.queue.join`: if the plank is occupied, the GUI and `/walk play` tell them
to wait nearby and try again when the runner finishes. Grant the leaf explicitly only to
groups that should reserve the next free arena. Queue status and leave remain available
under the normal play permission so a revoked player can inspect or leave stale state safely.

Examples:

```text
/walk
/walk play
/walk queue join
/walk queue status
/walk queue ready
/walk top all-time
/walk top season
/walk top combo
/walk stats flawless
/walk settings particles reduced
/walk settings sounds off
/walk admin validate
/walk admin arena create summer-main
/walk admin queue pause
/walk admin season create Summer 2026
/walk admin season list
/walk admin export all-time
/walk admin reward list unknown 20
/walk admin reward inspect 00000000-0000-0000-0000-000000000000
/walk admin run list unknown 20
/walk admin run inspect 00000000-0000-0000-0000-000000000000
/walk admin run player 00000000-0000-0000-0000-000000000000 20
/walk admin status
/walk admin doctor
/walk debug all
```

## Permissions

Defaults below are declared in `plugin.yml`. Leaf strings may be remapped under `permissions` in `config.yml`, but the static parent/child graph in `plugin.yml` does not change at runtime. If a leaf is remapped, define and grant that replacement in the permission manager explicitly. Configuration validation prevents a privileged node from reusing a player node.

| Permission | Declared default | Controls |
| --- | --- | --- |
| `infinityparkour.player` | Everyone | Parent granting the standard player leaves. |
| `infinityparkour.admin` | Operators | Parent granting every administrative leaf and acting as the administrative bypass. |
| `infinityparkour.opengui` | False; player parent grants it | `/walk` GUI. |
| `infinityparkour.play` | False; player parent grants it | Starting runs plus queue status/leave. |
| `infinityparkour.queue.join` | False; explicit grant or admin parent | Join/accept the optional FIFO reservation queue. It is deliberately absent from the standard player parent. |
| `infinityparkour.leavearena` | False; player parent grants it | `/walk leave`. |
| `infinityparkour.statscmd` | False; player parent grants it | Personal statistics command/GUI action. |
| `infinityparkour.topcmd` | False; player parent grants it | All-time and active-season top ten, including GUI lore. |
| `infinityparkour.info` | False; player parent grants it | `/walk info` and `/walk version`. |
| `infinityparkour.help` | False; player parent grants it | Permission-filtered help. |
| `infinityparkour.preferences` | False; explicit grant or admin parent | View/change UUID-owned particle, sound, and title preferences through `/walk settings`. |
| `infinityparkour.reload` | False; admin parent grants it | Root/nested reload. |
| `infinityparkour.admin.open` | False; admin parent grants it | Open another player's menu. |
| `infinityparkour.admin.debug` | False; admin parent grants it | Status/debug pages and the privacy-safe `/walk admin doctor` support report. |
| `infinityparkour.admin.stop` | False; admin parent grants it | Stop another run without rewards. |
| `infinityparkour.admin.recover` | False; admin parent grants it | Retry restoration/quarantine recovery. |
| `infinityparkour.admin.validate` | False; admin parent grants it | Read-only configuration validation. |
| `infinityparkour.admin.arena` | False; admin parent grants it | Guarded arena editor. |
| `infinityparkour.admin.queue` | False; admin parent grants it | Queue inspection and control. |
| `infinityparkour.admin.season` | False; admin parent grants it | Season lifecycle changes. |
| `infinityparkour.admin.export` | False; admin parent grants it | UUID-only CSV/JSON exports. |
| `infinityparkour.admin.reward` | False; admin parent grants it | List, inspect, resolve, or abandon durable reward evidence without replay. |
| `infinityparkour.admin.investigate` | False; admin parent grants it | Read-only bounded retained-run list/inspection by UUID, status, arena, or season. |

## PlaceholderAPI

The built-in expansion identifier remains `infinityparkour`. No eCloud expansion is needed. Top-position placeholders support positions 1 through 10 and fields `name`, `score`, or `rank`; a missing row returns an empty string.

### Saved and active-player values

| Placeholder | Value |
| --- | --- |
| `%infinityparkour_score%` | UUID-linked all-time personal best, or `0`. |
| `%infinityparkour_rank%` | All-time competition rank, or `0`. |
| `%infinityparkour_percentile%` | `ceil(rank × 100 / entries)`, minimum `1`; `0` without a row. Lower is better. |
| `%infinityparkour_previous_best%` | Current saved all-time best; an active run's score is not projected until completion. |
| `%infinityparkour_best_delta%` | `max(current run score - saved best, 0)`. |
| `%infinityparkour_season_score%` | Active-season personal best, or `0`. |
| `%infinityparkour_season_rank%` | Active-season competition rank, or `0`. |
| `%infinityparkour_season_percentile%` | Active-season top percentage, or `0`. |
| `%infinityparkour_in_game%` | `true` only for an active run. |
| `%infinityparkour_current_score%` | Current run score, or `0`. |
| `%infinityparkour_current_combo%` | Current within-target streak, or `0`. |
| `%infinityparkour_maximum_combo%` | Longest streak in the active run, or `0`. |
| `%infinityparkour_current_flawless%` | `true` while every completed transition in the active run remains within the combo target. |
| `%infinityparkour_combo_score%` | Saved Combo personal best, or `0`. |
| `%infinityparkour_combo_rank%` | Saved Combo competition rank, or `0`. |
| `%infinityparkour_flawless_score%` | Saved Flawless personal best, or `0`. |
| `%infinityparkour_flawless_rank%` | Saved Flawless competition rank, or `0`. |
| `%infinityparkour_particles%` | Personal particle mode: `full`, `reduced`, or `off`. |
| `%infinityparkour_sounds%` | Personal milestone sound preference. |
| `%infinityparkour_titles%` | Personal milestone title preference. |
| `%infinityparkour_current_arena%` | Active arena ID, or an empty string. |
| `%infinityparkour_elapsed_seconds%` | Active run elapsed seconds, or `0`. |
| `%infinityparkour_idle_seconds%` | Seconds since the last successful jump, or `0`. |
| `%infinityparkour_queue_position%` | FIFO position, or `0` when not queued. |
| `%infinityparkour_queue_ready%` | `true` while a readiness assignment is active. |

### Global, capacity, season, and top values

| Placeholder | Value |
| --- | --- |
| `%infinityparkour_version%` | Installed Bukkit plugin version. |
| `%infinityparkour_event_enabled%` | `true` only while new event participation is globally open. |
| `%infinityparkour_total_players%` | All-time leaderboard row count, including unresolved visible legacy rows. |
| `%infinityparkour_season_total_players%` | Active-season row count, or `0`. |
| `%infinityparkour_active_season_id%` | Active season UUID, or empty. |
| `%infinityparkour_active_season_name%` | Active season name, or empty. |
| `%infinityparkour_queue_enabled%` | `true` when the queue feature is enabled. |
| `%infinityparkour_queue_paused%` | `true` while readiness assignment is paused. |
| `%infinityparkour_queue_total%` | Total waiting plus ready players. |
| `%infinityparkour_queue_waiting%` | Players waiting without a readiness claim. |
| `%infinityparkour_queue_ready_count%` | Players with an active readiness claim. |
| `%infinityparkour_active_arenas%` | Number of active runs. |
| `%infinityparkour_available_arenas%` | Number of currently free arenas. |
| `%infinityparkour_total_arenas%` | Configured arena count. |
| `%infinityparkour_quarantined_arenas%` | Arena count withheld by unresolved block restoration or durable pending player-recovery evidence. |
| `%infinityparkour_top_1_name%` … `%infinityparkour_top_10_name%` | All-time name by position. |
| `%infinityparkour_top_1_score%` … `%infinityparkour_top_10_score%` | All-time score by position. |
| `%infinityparkour_top_1_rank%` … `%infinityparkour_top_10_rank%` | All-time competition rank by position. |
| `%infinityparkour_season_top_1_name%` … `%infinityparkour_season_top_10_name%` | Active-season name by position. |
| `%infinityparkour_season_top_1_score%` … `%infinityparkour_season_top_10_score%` | Active-season score by position. |
| `%infinityparkour_season_top_1_rank%` … `%infinityparkour_season_top_10_rank%` | Active-season competition rank by position. |
| `%infinityparkour_combo_top_1_name%` … `%infinityparkour_combo_top_10_rank%` | Combo top-ten `name`, `score`, or `rank`. |
| `%infinityparkour_flawless_top_1_name%` … `%infinityparkour_flawless_top_10_rank%` | Flawless top-ten `name`, `score`, or `rank`. |

Examples:

```text
/papi parse me %infinityparkour_score%
/papi parse me %infinityparkour_queue_position%
/papi parse me %infinityparkour_queue_waiting%
/papi parse me %infinityparkour_active_season_name%
/papi parse me %infinityparkour_current_combo%
/papi parse me %infinityparkour_combo_top_1_name%: %infinityparkour_combo_top_1_score%
/papi parse me %infinityparkour_season_top_1_name%: %infinityparkour_season_top_1_score%
```

## Configuration and version-2 behavior

The bundled [config.yml](src/main/resources/config.yml) is the authoritative defaults file.

| Section/key | Purpose |
| --- | --- |
| `configVersion` | Must be `2` when explicitly declared. Version 2 enables strict unknown-key rejection. |
| `event.enabled` | Global participation switch. Defaults to `false`; when false, the plugin remains loaded but refuses new runs and queue reservations and publishes zero available arenas. Set true and reload to open an event. |
| `startPositions` | Independent arena definitions with safe IDs, start locations, and optional exits. Starts, safety volumes, world bounds, headroom, and overlaps are validated. Custom exits additionally require collidable non-hazardous support, two passable non-liquid blocks, and placement outside every protected arena volume. |
| `parkourBlocks` | Non-empty safe platform palette used by `theme.active: custom`. Air, gravity, flammable, unstable, dangerous, non-item, unsuitable, and known stateful/workstation block families are rejected. The live `JACK_O_LANTERN` and legacy `STONE` choices remain eligible. |
| `theme.active` | `custom` uses the top-level palette, particle, and sound profile. Any other value selects an exact lowercase entry from `theme-presets`. |
| `sounds.*` | Backward-compatible `custom` sound profile containing `start`, `landing`, `milestone`, `combo`, `finish`, and `failure`. |
| `theme-presets.*` | Up to 32 lowercase named presets. Each has 1–16 safe `parkourBlocks`, a validated untyped particle, and may define a complete six-cue sound profile. A historical/operator preset without `sounds` inherits the validated top-level profile. The exact bundled roster is `default`, `valentine`, `easter`, `summer`, `halloween`, `thanksgiving`, `christmas`, and `anniversary`. |
| `*.sounds.<cue>` | `enabled`, `provider: default|cmi`, a nonblank `sound`, `volume` from 0.0–1.0, and `pitch` from 0.5–2.0. `default` resolves a Paper sound registry key; `cmi` accepts only a bounded token and requires enabled CMI at playback. |
| `gameplay.fallDistance` | Run-ending drop, from 6 through 64 blocks. |
| `gameplay.horizontalRadius` | Generation radius, from 3 through 64 blocks. |
| `gameplay.maximumRunSeconds` | Absolute run limit, 30–86,400 seconds. |
| `gameplay.idleTimeoutSeconds` | No-progress limit, at least 15 and no greater than the run maximum. |
| `gameplay.onlyReplaceAir` | Prevents generated target blocks from replacing occupied world blocks. The start block is always snapshotted/restored. |
| `queue.*` | Enablement, 0–600 second join cooldown, 5–300 second readiness, and 2–60 second reminder interval. |
| `permissions.queueJoin` | Remappable default `infinityparkour.queue.join` leaf for FIFO join/readiness. The static default is false and the standard player parent does not grant it. |
| `arenaSelection.*` | Selection policy and required configured ID for `PINNED`. A busy pinned arena safely falls back to least-recently-used. |
| `particle.*` | Optional modern Paper particle and count. |
| `milestones.*` | Enabled flag, unique scores, untyped particle, particle count, and 0–60 second feedback cooldown. An explicitly configured historical `milestones.sound` is retained as the custom profile's milestone fallback when no explicit top-level milestone cue exists. |
| `categories.combo.*` | Separate-category enablement and a 1–60 second maximum gap. Classic is never modified. |
| `antiCheat.*` | Conservative projectile/riptide/exploit-teleport blocking, 0–2000 ms minimum jump interval, and 0–600 second per-kind audit cooldown. Violations never auto-ban. |
| `runFinishCommands` | Master switch for trusted console reward commands. |
| `rewards.onlyOnPersonalBest` | Makes otherwise matching rewards ineligible unless the all-time best improved. |
| `rewards.allowedCommandRoots` | Effective list of 1–32 exact trusted roots; an omitted key inherits the bundled defaults. Entries are trimmed/lowercased, must match `[a-z0-9][a-z0-9:_-]{0,127}`, and must be unique after normalization. Bundled defaults are `say`, `cmi`, `uf`, and `welcomes`; remove unused providers. A configured command outside the list is always a validation error, even while rewards are disabled, and runtime preparation checks the list again before lookup/dispatch. |
| `finishCommands` | Score-range command tiers using `{{playerName}}`, `{{playerUuid}}`, and `{{score}}`. Across overlapping tiers, any one score may match at most 100 non-empty commands. |
| `permissions.*` | Runtime leaf-node remapping with player/admin privilege separation. |
| `database.type` | Must be `SQLITE`. MySQL/MariaDB is deliberately rejected. |
| `database.sqlite.file` | Relative, non-symlinked path confined beneath the plugin data directory. |
| `database.sqlite.busyTimeoutMillis` | SQLite busy timeout; the default is 5000 ms. |
| `database.sqlite.migrationBackupRetention` | Verified automatic migration backups retained (2–100, default 5). Nonmatching operator-named files are untouched. |

Switching themes changes only appearance for new runs. Edit one value, validate, and reload:

```yaml
theme:
  active: summer

event:
  enabled: true
```

```text
/walk admin validate
/walk admin reload
```

The bundled choices are:

| Preset | Platform palette | Placement particle |
| --- | --- | --- |
| `default` | `EMERALD_BLOCK` | `TOTEM_OF_UNDYING` |
| `valentine` | `PINK_CONCRETE`, `RED_CONCRETE` | `HEART` |
| `easter` | `LIGHT_BLUE_CONCRETE`, `YELLOW_CONCRETE`, `PINK_CONCRETE` | `HAPPY_VILLAGER` |
| `summer` | `PINK_CONCRETE` | `CHERRY_LEAVES` |
| `halloween` | `JACK_O_LANTERN` | `TOTEM_OF_UNDYING` |
| `thanksgiving` | `ORANGE_CONCRETE`, `BROWN_CONCRETE`, `YELLOW_CONCRETE` | `COMPOSTER` |
| `christmas` | `SNOW_BLOCK`, `WHITE_CONCRETE` | `SNOWFLAKE` |
| `anniversary` | `GOLD_BLOCK`, `DIAMOND_BLOCK` | `TOTEM_OF_UNDYING` |

Particles and sounds are sent only to the runner. The player's existing `/walk settings particles full|reduced|off` and `/walk settings sounds on|off` preferences remain authoritative, so a theme cannot override accessibility choices. Exactly one landing-family sound is selected per jump: milestone first, otherwise combo on a multiple-of-five streak, otherwise landing. `finish` means an explicit `/walk leave`; falling, timing out, movement disqualification, or a course error uses `failure`. Administrative cleanup, reload, shutdown, quit, death, and external teleport cleanup stay quiet. `custom` remains backward compatible with historical files and uses the top-level `parkourBlocks`, `particle`, and `sounds` sections.

Example CMI cue (CMI is optional and direct Paper sounds remain the bundled default):

```yaml
theme-presets:
  summer:
    sounds:
      start:
        enabled: true
        provider: cmi
        sound: ENTITY_PLAYER_LEVELUP
        volume: 0.8
        pitch: 1.0
```

There is deliberately no event scheduler. Operators choose the theme and set `event.enabled: true`, validate/reload, announce the event, then set it false and reload when the event closes.

Legacy configuration behavior is deliberate:

- If `configVersion` is absent, the file is treated as legacy. Missing current keys inherit bundled defaults in memory.
- `/walk admin validate` reports that the version is absent and reports legacy/unknown keys—including keys inside arena, `endPos`, and reward-tier mappings—as warnings; those unknown keys are ignored. Legacy MySQL enable flags are a deliberate exception: setting one to `true` is still rejected fail-closed.
- The file is not silently rewritten. Compare it with the bundled file, add the intended modern values, remove obsolete keys, and then add `configVersion: 2`.
- With explicit `configVersion: 2`, unknown keys are errors at the top level and inside every arena, nested `endPos`, and reward-tier mapping. A present `endPos` must be a mapping even when custom exit use is disabled. Any explicit version other than `2` is an error.
- A failed validation/reload keeps the previously active runtime settings. A successful reload drains runs and queue state before rebuilding arenas.

### Translation formatting and literal values

Translation strings are trusted operator templates. Existing unmarked values continue to use legacy ampersand formatting, so custom live `translations.yml` files remain backward compatible. Prefix one trusted value with `minimessage:` to opt only that value into MiniMessage; for example, `minimessage:<!italic><color:#bde0fe><bold>WalkThePlank</bold></color>`. Bundled GUI item names and lore use this explicit marker plus the shared 1MB pastel palette: pale blue `#bde0fe`, readable body white `#f2f5f7`, soft value gray `#d8e2dc`, pink `#ffc8dd`/`#ffb3c1`, and action gold `#ffd166`.

The inventory window title has a narrower readability rule: `mainGui.title` supplies text, all of its legacy/MiniMessage styling is removed, and `mainGui.titleColor` applies one validated `#RRGGBB` color. The default is the near-black charcoal `#111827`; use `#000000` for complete black. The 54-slot menu draws `mainGui.fillItem` only on its outer frame and leaves unused inner slots empty. The bundled pane is `LIGHT_BLUE_STAINED_GLASS_PANE`, and the historical `WHITE_STAINED_GLASS_PANE` value is upgraded to light blue in memory. Slots 45/52/53 replace three bottom-frame panes with the viewer's `PLAYER_HEAD`, an `ARROW` back to `/menu`, and a `BARRIER` close control. The online player's already-loaded Paper profile supplies the head skin without a username/network lookup. The exact historical and immediately previous bundled tutorial/play/scoreboard wording upgrades in memory to the proofread pastel defaults, while the new action sections are inherited from bundled defaults and genuinely customized operator text remains authoritative. None of these compatibility behaviors rewrites `translations.yml`; `mainGui.useFillItem: false` still disables the remaining frame.

Every GUI display name and every nested lore component is recursively decorated with `italic=false` after parsing and literal placeholder insertion. This is a renderer-level guarantee: legacy `&` text, modern MiniMessage, dynamic leaderboard records, and even a nested configured `<italic>` tag cannot make plugin tooltip text italic.

Runtime `{{placeholders}}`—including player and season names, database values, permission nodes, counts, the tutorial's `{{platformBlock}}` label, and the head's `{{playerName}}`, `{{playerScore}}`, `{{playerPlace}}`, `{{totalPlaces}}`, and `{{percentile}}` values—are replaced literally only after the trusted template is parsed. `{{platformBlock}}` follows the current `parkourBlocks` setting: one to three distinct materials are named in readable lowercase English, while longer lists use the bounded label “configured platform.” Replacement text cannot introduce legacy colors, MiniMessage tags, click/hover events, or other template behavior. Do not add `minimessage:` to an untrusted dynamic value; the marker belongs only on an operator-controlled template.

Reward commands are trusted console authority. They must be single-line, at most 2048 characters, and use a safe command root. When rewards are enabled, unavailable roots fail configuration validation. Matching ranges may overlap deliberately; validation warns because commands from every matching tier will be combined. An inclusive interval sweep rejects any configuration in which a possible score would combine more than the durable ledger limit of 100 non-empty steps.

## SQLite schema v3, migration, backup retention, and UUID ownership

The only supported backend is `plugins/InfinityParkour/database.db` unless a different confined relative filename is configured.

Before opening SQLite, the repository acquires a non-blocking exclusive operating-system lock on the safe regular sibling file `database.db.walktheplank.lock` (or `<configured-filename>.walktheplank.lock`). A second WalkThePlank repository targeting the same database fails startup instead of sharing the file. The lock is held until an orderly repository drain releases it; after an unsuccessful close it remains owned until a later completed close or process exit. The small lock file may remain on disk when unlocked and must not be used as a reason to delete data or assume another process is running. Symlinked or non-regular lock targets fail closed.

The Gradle wrapper distribution is SHA-256 pinned, runtime/compile/test dependencies are locked, dependency checksums are verified, and CI actions are pinned to reviewed commit SHAs with weekly dependency update proposals. Release archives also use fixed entry ordering and timestamps. These controls are intended to support byte-for-byte comparison under the same pinned build environment; the release checklist still requires two observed clean-build hashes rather than assuming reproducibility across Java vendors or patch releases.

Schema v3 contains:

- `scoreboard`: UUID-owned all-time bests and last-known names;
- `schema_migrations`: applied schema records;
- `seasons` and `season_scores`: explicit season lifecycle and bests;
- `run_history`: durable starts/completions/interruption states; the runtime retains the newest 10,000 prunable `COMPLETED`/`ABORTED` rows plus all `STARTED`, `UNKNOWN`, and uncertain-ledger evidence;
- `reward_plans` and `reward_steps`: redacted reward execution state;
- `reward_tombstones`: compact terminal plan/run/idempotency evidence retained after an old terminal run and its full ledger are pruned.
- `player_preferences`: UUID-owned particle/sound/title choices independent of leaderboard rows;
- `category_scores`: separate Combo and Flawless personal bests;
- `run_category_scores`: the exact category projections committed with each run.

Before mutating an existing non-empty database that requires migration, the plugin creates a sibling backup named like:

```text
database.db.pre-migration-v3-<epoch-millis>.sqlite
```

The backup is made with SQLite `VACUUM INTO` and must pass `PRAGMA quick_check` before migration proceeds. At every startup, exact automatic names matching `<database>.pre-migration-v<schema>-<epoch>[-suffix].sqlite` are treated as plugin-owned retention candidates: every candidate is a safe regular file and must pass `quick_check`, then only the oldest excess files beyond `database.sqlite.migrationBackupRetention` are removed. The default retains five and the accepted range is 2–100. A corrupt or non-regular automatic candidate fails startup; an operator-named file that does not match the exact pattern is never pruned.

The schema migration runs transactionally and validates required columns, indexes, constraints, and foreign keys. Persistence accepts only vanilla Java last-known names matching `[A-Za-z0-9_]{3,16}` and non-negative scores no larger than Java's integer maximum; the fresh schema also records matching constraints. A database with a newer unsupported schema, malformed nonblank UUID, duplicate nonblank UUID, incompatible table/index definition, unsafe path, or failed backup disables the plugin safely.

Build 008's disposable-copy schema-v3 rehearsal preserved all **100** rows and all **100** UUIDs, score sum **4256**, maximum score **149**, the unchanged Classic top ten, and a valid `PRAGMA quick_check`; its automatic backup passed retention verification and `_resources` remained unchanged. Build 023 has no database-schema change, but repeat the same invariants against its frozen candidate rather than treating older evidence as approval.

Migration never guesses identity from a username. Blank UUID text is normalized to unresolved `NULL`; malformed or duplicate nonblank UUIDs fail. Unresolved rows can remain visible in rankings, but player-specific lookup cannot claim them and UUID-only export refuses the entire affected snapshot. No CMI database or online API is queried automatically.

## Seasons, run history, and rewards

A run UUID is persisted as `STARTED` before gameplay mutates arena blocks. The active season, if any, is captured in that same start record. Completion atomically changes the run to `COMPLETED`, projects a positive score into all-time and captured-season personal bests, and—when an eligible non-empty reward was prepared—creates the redacted reward plan and steps in the same SQLite transaction. A failure or conflict at any point rolls back the complete transaction. On startup, any abandoned `STARTED` run is changed to `UNKNOWN` with no score projection.

Prunable terminal run history is limited to the newest 10,000 `COMPLETED`/`ABORTED` records. Active `STARTED` runs, every `UNKNOWN` run, and runs whose reward plan is `PENDING`, `IN_PROGRESS`, or `UNKNOWN` are protected from automatic pruning. There is intentionally no automatic guess that converts an `UNKNOWN` run into a safe terminal outcome; staff can inspect it, and an explicit no-score reconciliation workflow remains future work. When an older eligible terminal run and full ledger are pruned, a compact tombstone preserves its plan ID, run ID, idempotency key, terminal status, and completion/prune times so the same reward cannot be prepared again.

Staff can query this retained evidence with `/walk admin run` under a permission separate from reward resolution. Investigation ownership is UUID-only, results are newest-first and limited to 100, and linked plans expose only their UUID/status. A row already removed by retention is not recreated or guessed from leaderboard/name data.

For eligible positive-score fall/leave rewards (a score of zero never creates or dispatches a plan):

1. The selected configuration-time reward plan is expanded on the primary thread. Every root must be in the captured configuration allow-list before any root is looked up in Paper's command map; only then are all roots preflighted.
2. One database transaction completes the run, projects the score, and creates a deterministic durable plan containing only run/plan IDs, an idempotency key, command roots, and command hashes.
3. After commit, personal-best and cancellable reward-plan events run on the primary thread. A personal-best-only plan that did not produce a new best is finalized without dispatch.
4. A per-player completion barrier prevents a new run from starting until this plan reaches its dispatch/finalization boundary.
5. Each step is committed as `DISPATCHING` before its console command is handed to Paper.
6. Its handled/failed/unknown result is persisted before the next step is attempted.

Important uncertainty boundary:

- Bukkit command dispatch is not transactional and most downstream plugins do not support an idempotency token.
- A crash after `DISPATCHING` is recorded cannot prove whether the external command ran. Startup converts that step to `UNKNOWN`, skips remaining steps, and never replays it automatically.
- A crash after plan persistence but before the first atomic claim leaves the exact all-`PENDING` plan unchanged. The repository can recognize an exact internal retry, but the plugin intentionally does not reconstruct or replay raw commands at startup. Staff must find it with `/walk admin reward list pending`, inspect it, and use `/walk admin reward abandon <plan-uuid> confirm` to close it without dispatch.
- A command that throws has an `UNKNOWN` outcome; a command that returns unhandled is `FAILED`; later steps are skipped.
- Run completion/score projection and reward-plan creation cannot commit separately. Exact completion retries do not dispatch again, a changed reward shape is rejected, and an already-completed run cannot be retroactively given a new plan.
- A missing command root or an ordinary command-map preflight failure produces a redacted, non-executable plan that is atomically stored and then finalized without dispatch. If reward-intent construction throws outside that contained preflight path before a request exists, the run still completes without a plan and the failure is audited/logged. Neither case is a replay instruction.
- Raw command text is intentionally absent from SQLite and the structured audit log. Root/hash evidence helps investigation but cannot reconstruct secret arguments or prove a downstream economic side effect.

An `UNKNOWN` reward is always an operator investigation, not a replay instruction. `/walk admin reward resolve ... confirm` changes only durable evidence and emits a structured audit event. It never calls Paper's command dispatcher.

## Restoration journal and quarantine

Before each generated block is placed, WalkThePlank captures the exact original one-block Paper structure, material/block data, world UUID/name, location, expected plugin state, run/arena IDs, release identity, and integrity hashes on the primary thread. Immutable scalar values and cloned structure bytes then go to the bounded `walktheplank-recovery-writer`. That writer hashes, writes, fsyncs, atomically moves, and directory-fsyncs the `.pending` record. Only after its future completes successfully does the primary thread revalidate the exact player, run generation, arena lease, block lease, live state, and structure fingerprint before changing the world.

Before any run changes the captured player state or performs the arena teleport, the same recovery writer durably persists a privacy-bounded record beneath `plugins/InfinityParkour/player-recovery-journal/`. It contains only player/run UUID ownership, the arena ID, return world UUID and finite coordinates/orientation, and the captured health, food, saturation, exhaustion, walk speed, flight, and collision fields. It never stores a player/world name, inventory, IP, command, or chat. Velocity and fall distance are deliberately normalized to zero during preparation/cleanup rather than captured or restored.

The recovery queue is bounded and single-writer FIFO; saturation rejects the operation and fails the run closed without executing durability work on the caller. Cancellation-safe preparation tickets settle a pre-publication cancellation cleanly and use exact retryable deletion when cancellation races a published append. If atomic rename succeeded but the parent-directory fsync failed, the future fails as uncertain while the exact record remains published and ownership-blocking; a failed discard also retains it until a later exact discard succeeds. Runtime readers use immutable published journal views, so main-thread ownership and health checks never wait behind a writer monitor that is inside `fsync`. An exclusive lifetime lock is acquired before either journal is opened or temporary files are inspected, remains held while an old writer can still run, and releases automatically only after that writer terminates. A separate bounded and independently locked `walktheplank-operations-writer` owns audit, export, and configuration file work.

Course generation keeps at most the current, target, and already-durable successor platforms placed. The successor is prepared while the player traverses the current jump. On landing, the primary thread promotes the exact prevalidated successor, captures the following candidate, restores and verifies the predecessor, then queues only predecessor journal deletion and directory sync. Its block lease is released only after that durable deletion completes successfully. If the successor is not ready yet, the standing landing is rechecked when the completion reaches the primary thread instead of mutating Bukkit state asynchronously.

Player-recovery records are integrity checked, limited to 1,024 files, 16 KiB per file, and 8 MiB in aggregate, and use the same fsynced temporary-file plus required atomic-move discipline as block restoration. Malformed, duplicated, orphaned, or ownership-mismatched evidence is retained and fails closed; it is not guessed from. A byte-for-byte copy that preserves the exact valid player/run/arena ownership cannot be distinguished from its source and therefore remains operator-owned recovery evidence. A journal record owned by a live active/pending run is write-ahead protection, not crash quarantine, so it does not freeze the healthy runner. During asynchronous orphaned-evidence ownership verification, the affected online player is quarantined from movement, teleport, damage, inventory, item, entity, and world interactions. A valid reconnect recovery verifies the retained run UUID/player/arena ownership, restores state, then live-validates the configured exit when applicable, captured return, and loaded-world spawns against hazards and every configured arena volume. The exact record is deleted only after the applicable cleanup succeeds. A terminal accepted external teleport is confirmed on the next server tick at the same run, world, and destination before state is restored and evidence is cleared; cancellation, destination modification, or failed commit retains the run and journal. Terminal death/respawn preserves respawn health, hunger, and destination while clearing temporary movement/flight/collision state.

The current release rejects admission when the movement-speed attribute base differs from Paper's player-specific entity default or any modifier other than the exact vanilla sprint modifier is present. It deliberately does not compare with `Attribute#getDefaultValue`, because Paper documents that registry value as non-contextual; the player baseline comes from `EntityType.PLAYER#getDefaultAttributes`. It repeats this check before activation and every second, ending a run without rewards if the attribute becomes ineligible, and it freezes held-slot changes while active. It never edits, removes, or serializes player equipment; the beta matrix must still exercise idle and sprinting admission plus custom equipment/PDC items and external attribute providers.

On controlled cleanup or startup recovery:

- the expected plugin block is restored from the original structure and verified;
- an already restored block is accepted only when both state and structure fingerprint match;
- a third-party change becomes a conflict and is not overwritten;
- an unloaded/missing world remains pending;
- an affected arena remains unavailable while any restoration record is unresolved.

At controlled run end, every candidate—the configured exit, captured return, and each world-spawn fallback—is revalidated against live blocks and every configured arena volume immediately before teleport. If every safe candidate is rejected, the durable player-recovery record remains pending and the arena is quarantined rather than being assigned while the previous player may still be inside it. A player join or `/walk admin recover` retries state and return without trusting a stored destination blindly. `/walk debug health` exposes bounded pending/invalid/healthy status without paths or record contents.

An unresolved player-recovery arena is never returned to the free pool, including after a restart; unreadable evidence whose arena cannot be established conservatively withholds every arena. There is deliberately no online “discard recovery” command. If exact run ownership cannot be verified after a database/data-folder mismatch, stop the server, back up the complete `plugins/InfinityParkour/` folder and world/player data, and first restore the matching database plus journal set from the same known-good backup. If no matching backup exists, preserve the journal file unchanged, use `/walk admin run inspect <run-uuid>` and offline records to investigate, and have an authorized operator manually establish the player's safe state/location before moving—not deleting—the evidence out of the active journal. Start only on a disposable copy first; retain the moved evidence and audit note. Never guess ownership, edit journal properties/hashes, or clear a record merely to free capacity.

Use `/walk admin status` for pending/conflicted counts and `/walk admin recover` after correcting a missing-world condition. Do not manually delete journal records merely to free an arena. Preserve the journal and world together for investigation.

The journal is stored under `plugins/InfinityParkour/restoration-journal/`. Startup rejects malformed, oversized, duplicate-target, symlinked, integrity-invalid, or unsupported `.pending` records instead of silently discarding them. Recognizable regular pre-commit temporary files created by the journal are deleted during startup; an unknown `.tmp`, symlink, or other non-regular temporary entry fails startup for investigation.

Safety limits are 1,024 pending records, 16 MiB of original structure data per snapshot, 24 MiB per encoded record file, and 256 MiB across pending record files. A limit is checked before placement and fails closed; it is not permission to truncate restoration evidence.

## Operations, health, and audit

`/walk admin status` and `/walk debug health` report:

- active/free/configured arenas;
- quarantined arena count, pending/conflicted block restorations, and pending/invalid player recoveries;
- SQLite score rows and snapshot time;
- pending mutations and last successful write;
- retained runs and uptime run counts;
- queue totals/state;
- active season;
- reward-plan status counts when rewards are enabled;
- sanitized last database/runtime failure categories.

`/walk admin doctor` supplements those snapshot pages with a copy/paste-safe support report. The command immediately acknowledges that work is pending, then schedules a read-only SQLite `quick_check` and storage inspection on the repository worker rather than blocking the primary server thread. The completed report contains the exact source commit and clean/dirty provenance, compile targets versus the current Java/Paper runtime, optional-hook and configured command-root availability, journal/quarantine and uncertain-reward totals, queue/task health, recovery/operations writer queue and failure counters, audit-chain verification/sequence/anchor state, integrity result, database and WAL byte counts, retained/configured/pruned migration-backup counts, and probe latency.

Doctor output deliberately excludes player and season names, coordinates, filesystem paths, raw reward commands or arguments, credentials, SQL text, stack traces, and exception messages. A failed probe returns a bounded safe failure category and does not expose the underlying path or database contents.

The structured audit stream is:

```text
plugins/InfinityParkour/audit/audit.jsonl
```

Each line carries a chain UUID, monotonic sequence, previous hash, and SHA-256 record hash. Startup verifies the current file plus every retained chain archive against `audit-state.properties` and `audit-anchor.properties`; a modified record, broken link, truncation, missing checkpoint, or attempt to reclassify chained data as legacy fails plugin startup. The sole checkpoint-recovery exception is a provable empty first-start chain interrupted after its zero-sequence anchor commit. An existing genuinely unchained `audit.jsonl` is preserved once as `audit-legacy-<timestamp>.jsonl` and a new verified chain begins—legacy evidence is never misrepresented as verified.

The stream rotates before exceeding 10 MiB and retains up to 10 sequence-ordered archives. Pruning stages the oldest archive under an exact temporary name, durably advances the verified retention anchor, and then deletes the staged file; startup either restores a pre-anchor staged archive or finishes deletion after an anchor commit, so a hard kill at that boundary does not produce a false tamper alarm. The caller prepares one immutable bounded payload; the operations writer appends and fsyncs its chained JSON line, updates state, rotates, prunes, and directory-syncs off the primary thread. It covers plugin/run/queue/reward/season/export/arena/recovery lifecycle plus rate-limited movement anomalies. It deliberately excludes IPs, inventory contents, chat, raw commands, credentials, coordinates, and arbitrary paths. This is tamper-evident, not a signature: an operator with filesystem authority could replace both logs and sidecars, so external immutable backups remain the stronger control. An unsafe/unwritable audit location or failed startup verification disables the plugin; a later queue/append failure marks audit health degraded and logs/counts it without caller-thread fallback.

Runtime reload and arena edits capture file bytes on the operations writer, parse and validate captured content on the primary thread where Paper world lookups are legal, then return to the worker for compare-before-write, fsync, atomic rename, and final disk-state verification. Publication uses a configuration generation CAS. Arena layout mutation additionally requires an empty queue and no active, pending, quarantined, or recovery-owned work. A committed edit remains bound to its exact backup bytes until final verified activation; disable queues a FIFO reconciliation barrier so a committed-but-unactivated edit cannot escape shutdown.

Player operator attribution and an explicit `player`/`system` actor category are included for arena edits, explicit restoration recovery, queue pause/resume/drain, admin stop/open, reload, validate, seasons, exports, and reward/run investigation. Safe target/result/fingerprint fields are included where applicable; raw configuration, coordinates not needed for the decision, and command bodies are excluded.

## Bukkit API and events

`WalkThePlankApi` is registered through Bukkit's services manager at `ServicePriority.Normal` and unregistered on disable. Consumers should call it on the primary server thread.

Read-only methods expose:

- the immutable release label;
- UUID-based all-time player record;
- an all-time leaderboard with a caller limit from 0 through 100;
- an active-run snapshot by UUID;
- active/available/quarantined/configured arena capacity;
- the current active-season identity/state, when present;
- a per-player queue view with enabled/paused state, total queued, position, and optional readiness deadline;
- redacted recent runs with a caller limit from 0 through 100, including linked season and retained reward-plan IDs when present.

Primary-thread events in `com.mrfdev.walktheplank.api.event` are:

| Event | Timing and mutability |
| --- | --- |
| `WalkRunStartEvent` | Before session/world allocation completes; cancellable. Cancellation persists the start as aborted and makes no course available to the player. |
| `WalkJumpEvent` | After a target landing advances the current score; read-only. |
| `WalkRunEndEvent` | After removal from active sessions and the primary-thread cleanup attempt; exposes reason, duration, authoritative score, and `cleanupComplete`. A `MOVEMENT_MODIFIED` run exposes score `0`, matching its database/category/reward projection; the observed pre-disqualification points remain audit evidence only. The cleanup flag is true only if both journal deletions have already durably settled and the exact leases/quarantine are gone; accepted-but-pending cleanup remains false. Score persistence is also asynchronous, so this event does not certify that the completion transaction has committed. |
| `WalkPersonalBestEvent` | After the new all-time best is durably persisted; read-only. |
| `WalkRewardPlanEvent` | Before an eligible durable plan begins dispatch; cancellable and exposes IDs/counts but no command text. Cancellation finalizes without dispatch. |

The current API intentionally does not expose mutable sessions, direct database handles, raw reward commands, historical season mutation, or asynchronous world access.

## Testing and event approval

The build-024 suite passes **290 tests across 71 test classes**, with zero failures, errors, or skips. It adds exact-roster, legacy-profile inheritance, sound-profile safety, CMI token-injection, volume/pitch-bound, and non-stacking cue-policy coverage to the existing queue permission, seasonal appearance, precise landing, two-platform durability, Paper runtime, restoration, GUI, migration, permission, audit, recovery, reward, queue, export, teleport, and reflection-locked event-contract suites. Earlier real-player testing approved sequential scoring, cleanup, the six-action GUI, and summer appearance; build 024 still requires its global-switch and eight-theme sound acceptance matrix.

Build 020 carries forward build 008's isolated destructive-test system. It never instruments the deployable JAR in place. Java 25's Class-File API transforms a separate copy and injects the test bridge only at the reviewed boundaries; a second, independently packaged Paper plugin drives scenarios and emits deterministic `WTP-SCENARIO PASS`, `FAIL`, `INFO`, and `PENDING` records. Build the artifacts and prove both negative and positive controls with:

```bash
./gradlew scenarioArtifacts verifyProductionScenarioIsolation
```

The test-only outputs are deliberately outside `build/libs/`:

```text
build/scenario-artifacts/TEST-ONLY-1MB-WalkThePlank-ScenarioHarness-v2.7.0-024.jar
build/scenario-artifacts/TEST-ONLY-1MB-WalkThePlank-v2.7.0-024-Failpoints.jar
```

`verifyReleaseJar` byte-scans the production JAR for scenario packages, commands, manifest/agent markers, canaries, every scenario-property prefix, and all 24 failpoint names. `verifyProductionScenarioIsolation` repeats that negative proof and requires the harness and instrumented copy to trigger positive controls, preventing a broken scan from reporting a false pass. It also builds a second instrumented copy and requires byte-for-byte identity. The runner refuses symlinked destructive roots and writes a bounded per-run nonce marker. Both the property-armed failpoint controller and the scenario plugin require that marker, the exact generated root/working directory/layout/data paths, and the bridge loaded by the instrumented target's own classloader.

Run the disposable Paper 26.2 profile with:

```bash
./scripts/run-controlled-scenarios.sh
./gradlew controlledScenarios
./gradlew controlledRuntimeCommitFailpoint
./gradlew controlledReleaseScenarios
```

The controlled runner owns the repeatable two-start/restart, PlaceholderAPI-absent/present, `/walk admin reload`, terminal disable/service-removal, fresh-JVM enable, and automated log-assertion phases. It deliberately does not re-enable a disabled plugin instance: Paper unregisters that instance's configured classloader, so the supported recovery proof is the next clean process start. In the player-assisted profile, `/wtpscenario gui stale` exercises the production scheduler/revalidation path with a harmless sentinel and no fabricated event or packet. Real players remain responsible for actual client GUI click, drag, hotbar/number-key, double-click, creative, cancelled/retargeted teleport, reconnect/state restoration, every run-exit cause, and simultaneous two-player queue/start observations. A headless `PENDING` marker is not a pass.

The 24 named points cover both player/restoration journal temp-write, fsync, rename, directory-fsync, and delete boundaries; block placement/restoration; start/return teleports; score completion; reward claim, dispatch, and outcome; CSV/JSON export renames; and configuration backup/candidate/disk/runtime commits. Every point supports `HALT`. Seventeen reversible/contained points also support `THROW` and releasable `BLOCK`; player/restoration journal rename and delete, configuration candidate rename, disk commit, and runtime commit are deliberately `HALT`-only because an exception or block timeout after those irreversible boundaries would create misleading same-process state. `HALT` is a process-kill test, not a simulated storage-device or host power loss. Inspect the actual SQLite `journal_mode`, `synchronous`, and `quick_check` result after every recovery phase rather than assuming durability settings. Also inspect retained temporary files: CSV and JSON are each atomically renamed but the pair is not one atomic transaction, so a halt after the CSV rename can leave a CSV without its matching JSON; interrupted export/config temporary files are not currently cleaned automatically.

Use [checklist-walktheplank.md](checklist-walktheplank.md) for the mandatory server-level review. It includes:

- the disposable 100-row live migration;
- queue fairness/readiness;
- all arena policies and editor rollback;
- seasons, run capture, history, and UUID-only exports;
- reward crash/uncertainty boundaries;
- restoration of ordinary blocks, signs, containers, and PDC-bearing tile state;
- conflict, missing-world, kill/restart, shutdown, GUI abuse, teleport-ordering, formatting-injection, doctor privacy/threading, permission, and dependency cases.

## Rollback

1. Stop Paper.
2. Preserve the failed candidate database, journal, audit, and logs separately for diagnosis.
3. Restore the previous JAR and its matched pre-deployment `plugins/InfinityParkour/` folder together.
4. Start with the previous supported Java/Paper combination.
5. Verify leaderboard rows/top ten and arena blocks before admitting players.

Rolling the database back discards scores written after that backup. Never reconcile them by username. If the current candidate's journal contains pending world restoration, resolve or preserve it before starting an old plugin that does not understand that journal.

During a clean disable, the repository stops accepting work, drains accepted operations for up to 15 seconds, and deterministically rejects accepted operations that never started if the timeout/interruption path is taken. A SQLite JDBC call that is already running may still outlive an unsuccessful repository close when the driver or operating system does not honor interruption. Treat the disable timeout as a no-go signal, preserve the database/logs, and do not immediately start a second server process against the same file.

The exclusive instance locks are an additional guard, not a substitute for process supervision. After a hard kill the operating system releases them, but operators must first prove the old Paper process is gone and inspect recovery evidence before starting a replacement. The regular lock files—`<database>.walktheplank.lock`, `.recovery-durability.lock`, and `.operations-io.lock`—may remain after ownership is released; their presence alone does not mean a writer is live. Never delete or replace them to “clear” ownership: on Unix, unlinking a pathname that another process still has locked can defeat the guard by creating a second inode. Symlink, directory, and other non-regular replacements fail closed.

## Credits and licensing

- Original InfinityParkour project: Meldiron.
- Historical fork and fixes: The456gamer.
- Modernization and custom build: mrfdev / 1MB.

No approved license file is currently present in this repository. Source availability or historical forks do not by themselves grant redistribution rights. Treat this as a private/custom build unless and until the relevant rights holders approve a license; do not publish binaries or source under an invented license.
