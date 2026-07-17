# Legacy artifact audit

This audit records what was recovered from the historical plugin JARs before the
Paper 26.2 modernization was frozen. It is evidence only; neither JAR is part of
the release artifact.

## 1.16.4 The456gamer scoreboard-fix JAR

- File: `_resources/infinityparkour-1.0.0-1.16.4-the456gamer-scoreboardfix.jar`
- Embedded plugin version: `InfinityParkour 1.0.10`
- SHA-256: `e495dfcf600de4acff9d4f1e55d041dcc47f988b796a73b718e4d7015df69137`
- Size: approximately 84 KiB

Every embedded `com/meldiron/...` class was byte-compared with the historical
compiled classes tracked by this repository. Every class matched except
`ScoreboardManager.class`. Bytecode inspection of that class found the expected
The456gamer scoreboard correction:

```sql
SELECT score, username FROM scoreboard ORDER BY score DESC LIMIT ?
```

The stale tracked compiled class omitted `ORDER BY score DESC`, but the tracked
Java source already contains the corrected query. The embedded `config.yml`,
`plugin.yml`, `translations.yml`, and `init.sql` are byte-identical to the
historical source resources. No additional command, database field, gameplay
feature, or hidden data migration was found in the patched JAR.

Conclusion: the only JAR-only compiled change was the known top-score ordering
fix, and that behavior is retained by the modern UUID-owned leaderboard.

## Recovered `WalkThePlank-2.0.0.jar` smoke artifacts

Two disposable Paper smoke profiles retained intermediate version-2.0.0 JARs
after the original `build/libs` copy was cleaned:

- SHA-256 `93a9602b6101a2fd9031d96b8e4de4fae05f071055597c6803bc91c05fa14e0a`
- SHA-256 `66a2a72fb4d1909b53816fa8f054d8d17b20b7f319b3eb8af0bd08cfd78fc2d6`

Their descriptors target Paper API 26.2 and their classes use the current
`com.mrfdev.walktheplank` package, so these are intermediate products of this
modernization—not an unknown 2024 binary. Both still bundle MySQL Connector/J,
advertise the earlier limited command/permission surface, and lack the v2.1.0
durability, queue, season, recovery, audit, API, validation, and reward-ledger
work. The final build therefore supersedes them; no behavior should be copied
back from either intermediate JAR.

## Release boundary

The release build must continue to prove that it contains SQLite JDBC but no
MySQL/MariaDB, Paper/Bukkit, PlaceholderAPI, live database, `_resources`, or
server files. See `checklist-walktheplank.md` for the executable verification
steps.
