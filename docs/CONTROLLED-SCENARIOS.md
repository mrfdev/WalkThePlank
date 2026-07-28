# WalkThePlank controlled Paper scenarios

This suite writes destructive test state only beneath `build/controlled-scenarios`. It reads
the designated Paper and PlaceholderAPI JAR inputs from `servers/` by default, but never
modifies that server profile, reads or copies `_resources/`, or uses retained player progress,
the live leaderboard database, or another normal server profile.

The scenario artifacts are intentionally separate from the production plugin:

- `build/scenario-artifacts/TEST-ONLY-...-Failpoints.jar` is an instrumented target.
- `build/scenario-artifacts/TEST-ONLY-...-ScenarioHarness-....jar` is a separate test plugin.
- `servers/Paper-26.2/plugins/PlaceholderAPI-2.12.3.jar` is the default deployable server
  plugin copied into the PlaceholderAPI-present phase.
- `verifyProductionScenarioIsolation` scans the production JAR and positive-control test
  artifacts. It fails if a scenario class, command, property, canary, or named failpoint leaks
  into production.

The runner uses Java 25 at
`/Library/Java/JavaVirtualMachines/jdk-25.0.4.jdk/Contents/Home/bin/java` by default. Override
that exact executable with `JAVA_BIN` only when another Java 25 installation is intentional.

## Automated two-start scenario

Run:

```bash
scripts/run-controlled-scenarios.sh
# Equivalent Gradle entry point:
./gradlew controlledScenarios
# Ordered release gate (two-start plus automated exit-97 recovery):
./gradlew controlledReleaseScenarios
```

Pass a different Paper 26.2 JAR either positionally or with `--paper`:

```bash
scripts/run-controlled-scenarios.sh /path/to/Paper-26.2.jar
scripts/run-controlled-scenarios.sh --paper /path/to/Paper-26.2.jar
```

Pass a different deployable PlaceholderAPI server plugin with:

```bash
scripts/run-controlled-scenarios.sh \
  --placeholderapi /path/to/PlaceholderAPI.jar
```

The runner rejects the small Maven API/development artifact because it lacks PlaceholderAPI's
relocated runtime metrics classes. It validates both `PlaceholderAPIPlugin` and the relocated
`CustomChart` class before Paper starts.

The default Paper path is `servers/Paper-26.2/Paper-26.2.jar`; it is read as an input and copied
into the disposable directory. The default PlaceholderAPI JAR is likewise read only as a plugin
input; no retained plugin data is copied. The server binds to `127.0.0.1` with `server-port=0`,
uses a named FIFO for console input, and writes both raw and ANSI-free logs under
`build/controlled-scenarios`.

An atomic `build/controlled-scenarios/.runner-lock` prevents two destructive runners from
cleaning or writing the same profile concurrently. Each mode replaces only its own exact child
directory, so `two-start`, `player-assisted`, and named `failpoint-*` evidence can coexist.
The runner refuses symlinked `build/` or runtime roots. Each generated server receives a
nonce-bound marker plus exact root properties; both the injected failpoint controller and the
harness require that marker, the Paper working directory, the `build/controlled-scenarios/`
layout, and the target/harness data paths to agree before destructive controls are available.

The runner also requires the local `sqlite3` CLI. Every clean phase records a read-only
`PRAGMA journal_mode`, `PRAGMA synchronous`, `PRAGMA foreign_keys`, `PRAGMA user_version`, and
`PRAGMA quick_check` report beside its server log. It requires schema v4 and accepts only
`quick_check=ok`; `foreign_keys` is recorded rather than required to be enabled because that
setting belongs to the short-lived read-only inspection connection, not the plugin's runtime
connection. It reads the Java/Paper/API/minimum-build release target from `gradle.properties`,
then parses the bootstrap log to require Java 25 and both the Paper runtime and stable API
identity at 26.2 build 84 or newer.

The automated sequence proves:

1. The test-only target and independent harness enable on Paper 26.2.
2. PlaceholderAPI is truly absent and its class is not visible to the target.
3. `/walk admin reload` completes while the target and service API remain available.
4. A terminal plugin-manager disable removes the API service.
5. A restart marker is prepared off the main thread with forced file contents, a required atomic
   rename, and directory fsync where the platform supports it.
6. The first JVM stops cleanly and releases its ephemeral port.
7. The same disposable data directory starts a second JVM, proving a fresh plugin enable.
8. PlaceholderAPI is present and the target expansion is registered on the fresh start.
9. Reload still succeeds, and the second terminal disable again removes the API service.
10. The restart marker is consumed by the same target version.
11. The second JVM stops cleanly, releases its port, passes read-only SQLite quick-check, and
    has no unexpected WARN, ERROR, harness, linkage, enable, or disable error markers.

Paper plugin class loaders are not treated as reloadable after a plugin-manager disable. The
suite deliberately uses disable as the final target action in each JVM and proves enable with a
fresh JVM. The only accepted warnings are the four standard lines caused by the disposable
profile's explicit `online-mode=false`.

Use `--skip-build` only to rerun artifacts already produced by a successful
`verifyProductionScenarioIsolation` task:

```bash
scripts/run-controlled-scenarios.sh --skip-build
```

## Hard-kill and recovery scenarios

The default hard-kill action is always `HALT`. A passing destructive scenario must log the
exact `ARMED` and `REACHED` marker, exit with code `97`, release its port, and then start the
same disposable profile without failpoint properties. A normal exit, exception-only failure,
or different exit code is not accepted as a hard-kill pass.

The bridge writes synchronous ARMED and REACHED records directly to the process stderr file
descriptor before halting, bypassing Paper's asynchronous redirected logger. The
crash-phase allowlist accepts only those exact raw records and the explicit offline-mode warning
(plus Paper's legacy stderr nag if a runtime wraps the descriptor). Any other warning, error,
exception, or harness failure rejects the scenario. Recovery uses the stricter normal-start
allowlist.

Seven post-publication boundaries are intentionally HALT-only:
`player_journal.after_rename`, `player_journal.after_delete`,
`restoration_journal.after_rename`, `restoration_journal.after_delete`,
`config.candidate.after_rename`, `config.after_disk_commit`, and
`config.after_runtime_commit`. The bridge rejects `THROW` or `BLOCK` at those irreversible
boundaries. The remaining named points also support controlled `THROW` and `BLOCK` when driven
directly through the in-game harness, although this shell runner deliberately uses only the
repeatable process-level `HALT` contract.

`config.after_runtime_commit` occurrence 1 is fully automated. The target first reaches normal
Paper readiness, then the runner sends `walk admin reload` to cross the runtime commit boundary:

```bash
scripts/run-controlled-scenarios.sh \
  --failpoint config.after_runtime_commit
# Equivalent Gradle entry point:
./gradlew controlledRuntimeCommitFailpoint
```

`--trigger-command` is written literally to the Paper console, sent once, and is not evaluated by
a shell. Therefore an occurrence greater than one is valid only when the selected player-assisted
or external action genuinely reaches that boundary enough times; one reload command cannot reach
runtime-commit occurrence 2.
Most world, teleport, score, reward, and player-journal boundaries require a real player.
Make that explicit:

```bash
scripts/run-controlled-scenarios.sh \
  --failpoint teleport.after_start \
  --wait-for-player
```

The runner prints the ephemeral address and waits for the instrumented JVM to exit. It never
pretends to join a player or synthesize a Minecraft packet. The timeout defaults to 600 seconds
and can be changed with `--failpoint-timeout` or `SCENARIO_FAILPOINT_TIMEOUT`.

Available named boundaries are:

- Player journal: `player_journal.after_temp_write`,
  `player_journal.after_temp_fsync`, `player_journal.after_rename`,
  `player_journal.after_directory_fsync`, `player_journal.after_delete`.
- Restoration journal: `restoration_journal.after_temp_write`,
  `restoration_journal.after_temp_fsync`, `restoration_journal.after_rename`,
  `restoration_journal.after_directory_fsync`, `restoration_journal.after_delete`.
- World and teleport: `block.after_place`, `block.after_restore`,
  `teleport.after_start`, `teleport.after_return`.
- Completion and rewards: `score.after_commit`, `reward.after_dispatch`,
  `reward_claim.after_commit`, `reward_outcome.after_commit`.
- Exports: `export.csv.after_rename`, `export.json.after_rename`.
- Configuration: `config.backup.after_rename`, `config.candidate.after_rename`,
  `config.after_disk_commit`, `config.after_runtime_commit`.

A recovery restart proves that Paper, the target, the service API, the harness, and SQLite can
start and stop cleanly after the hard halt. Feature-specific state must also be checked against
the expected journal, quarantine, score, reward, export, or configuration outcome for the
selected boundary; a clean process restart alone does not claim that gameplay outcome.

Use these recovery oracles; do not infer stronger atomicity:

| Boundary or crash window | Expected recovery limit |
| --- | --- |
| Startup property arm | A `-D` arm is consumed once per JVM and does not rearm on plugin lifecycle calls. |
| External teleport before next-tick run end | Recovery may restore the captured return state after reconnect. |
| World/player cleanup before asynchronous completion | A crash in the gap can leave an `UNKNOWN` outcome and no committed score. |
| `score.after_commit` | The score and a `PENDING` reward plan persist; pending plans are not automatically replayed. |
| `reward_claim.after_commit` / dispatching | A `DISPATCHING` step recovers as `UNKNOWN` and later steps are `SKIPPED`. |
| `reward.after_dispatch` | The command returned either true or false, but no durable delivery outcome exists yet. |
| `reward_outcome.after_commit` | The recorded outcome persists; a multi-step plan can legitimately be `PARTIAL`. |
| Export/config rename windows | CSV and JSON are not pair-atomic, a config transaction is not whole-file-set atomic, and temporary files are not scavenged automatically. |
| Journal conflict or missing world | Recovery quarantines the unsafe record instead of guessing or overwriting state. |
| Queued database work before commit | Uncommitted queued work is lost on a hard halt. |

Immediately after exit 97, the runner performs the same read-only SQLite PRAGMA report when the
database exists. The automated `config.after_runtime_commit` profile now halts during a post-start
administrative reload, so its crash phase normally has an initialized schema-v4 database and must
already pass `quick_check=ok`; the recovery restart repeats that proof.

`Runtime.halt(97)` accurately removes Java shutdown hooks and plugin-disable cleanup from these
process-level tests, but it is not proof against every host power-loss, storage-controller cache,
or filesystem failure mode. The temp-write, fsync, rename, and directory-fsync boundaries make
those durability assumptions explicit; hardware-loss validation still requires a disposable
host or VM test designed for that purpose.

## Player-assisted real-client profile

Start:

```bash
scripts/run-controlled-scenarios.sh --player-assisted
```

The runner installs PlaceholderAPI, prints the random local port, and waits for Return in the
terminal. Connect a real Java client before pressing Return.

Useful in-game commands include:

```text
/wtpscenario gui begin
/wtpscenario gui status
/wtpscenario gui stale
/wtpscenario teleport cancel
/wtpscenario teleport retarget
/wtpscenario teleport status
/wtpscenario reconnect arm
/wtpscenario reconnect status
/wtpscenario exits expect <reason>
/wtpscenario exits status
/wtpscenario contention
/wtpscenario status
```

For the GUI capture, exercise a normal click, shift-click, hotbar/number-key click, double-click,
creative click, and drag against filler or protected menu slots. The harness observes the exact
inventory identity and cancellation result. A normal Minecraft client cannot submit a click to
an already closed window, so `/wtpscenario gui stale` uses the production menu's real
`scheduleAction` callback with a harmless sentinel, rotates nonce/generation/exact inventory
through the real Paper view, and proves on the next ticks that the stale callback was rejected,
the target scheduler ran, the sentinel did not execute, and no action remains pending. It does
not construct an inventory event or fabricate a client packet.

For teleport cancellation and retargeting, begin a real run before arming the probe. For
reconnect recovery, begin a run, arm the probe, disconnect, reconnect, and manually verify the
return location, inventory, game mode, flight state, and attributes. Queue contention requires
two real online players and a runnable arena.

## Evidence boundary

Automated server evidence includes exact enable/start/stop behavior, two-start restart state,
PlaceholderAPI absent and present, command reload, terminal disable with API service removal,
fresh-JVM enable and service registration, explicit failpoint exit 97, recovery startup,
runtime/API build identity, stripped logs, read-only SQLite PRAGMA evidence, and port release.
Reflection tests in the normal test suite lock event priority and `ignoreCancelled` contracts.

Player-assisted evidence includes real GUI click/drag/hotbar/double-click/creative events,
cancelled and retargeted teleports, quit/join recovery, every gameplay exit cause, and
simultaneous run starts. Those cases are reported only when the harness observes real server
events. Neither this shell runner nor the harness fabricates protocol packets, and a
`status=PENDING` line is not a pass.
