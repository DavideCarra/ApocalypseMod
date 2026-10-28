# Self tests

Test harness for the mod, run on a real dedicated server. It is not part of the
mod: `runtest.sh` copies the classes into `src/main/java/.../selftest/` only for
the test run (add that folder to `.git/info/exclude`, and delete it before
building a release jar).

The harness starts when `run/selftest.properties` exists, runs one scenario,
writes its results to `run/<out_name>` and stops the server.

```
tools/selftest/runtest.sh <project_dir> <fresh|copy:<world_dir>> <out_name> scenario=<name> [key=value ...]
```

Scenarios:

- `generate`: generates the test areas (deep ocean, river, forest, frozen,
  swamp; `tall_taiga`, `jungle` and `mushroom` only when named in `regions=...`) and saves
  the world. Copy `run/world` somewhere and pass it with
  `copy:<dir>` to every other run: Minecraft world generation is not exactly
  the same from one run to the next, so comparisons need the same files.
- `compare`: processes the test areas (`day`, or `days=50,60,...` one after
  the other, `savedPhase` = phase saved for the chunks before) and dumps a hash
  of every section, the block entities, the heightmaps, the falling blocks and
  the saved phases. Run it on two versions of the code (for example with a
  `git worktree` of `main`) and diff the `AFTER` lines. `lightTicks=200` adds a
  server light check (sky light above the surface, light of the fires).
  `againDay` processes again on a later day (must change nothing after the
  end). `burnFiresBetweenDays=true` and `hashFireAsAir=true` compare a
  catch-up with a day by day run. `dumpTicks=true` lists the block ticks
  scheduled by the processing and, with `lightTicks`, dumps the regions again
  afterwards (`LATER`); `dropAshOverAshTicks=true` drops the ash ticks that
  find ash below them (to compare the old code with the new one).
  `dumpCounts=true` adds the count of every block state per chunk (`N`
  lines). The chunks of a day are processed as one batch when the code has
  batches (`Generator.beginBatch`), as `DayTracker` does.
- `clientlight`: logs in a fake player with a recording connection, lets the
  mod process the chunks it sees (`phaseChangeDay` simulates a phase change
  afterwards, `rapidDays=1,2,3` goes through those days one after the other
  with the real day event, `rapidInterval` ticks apart) and rebuilds the client
  light from the packets it received, compared with the server light. It also
  prints `LIGHT` (columns with less than full sky light above their top block,
  `SKYBAD`/`SKYCOL` lines for the first ones, fires, logs, leaves and giant mushroom blocks left near
  the surface) and `MOBS` (living mobs around the player by type, and how many
  of them are in the open).
- `perf`: the `clientlight` run, measured. For each window (`join`: the
  player arrives, `lightTicks` ticks; `phase`: after `phaseChangeDay`;
  `idle`: `idleTicks` more ticks) it prints the server tick times (harness
  time excluded), the time of the mod's end of tick work, the CPU time and
  allocations of every thread group (`main` server thread, `worker` light and
  world generation, `io`) and the packets sent to the player with their size,
  raw and compressed as on a dedicated server. `realistic=true` leaves fire,
  random ticks and the rest of the world running (only time, weather and
  natural spawning are frozen). `PERFTOP` lists the ticks with the most end of tick
  work of the mod (`t<tick of the window>=<ms>`). If the mod is instrumented with a public
  `Generator.PROF` array it is printed too (`PROF` lines). Run it on both
  versions, one after the other (never two at once), and repeat: single runs
  vary by 10-20%.
- `multiplayer`: two players log in through recording connections: both get
  the apocalypse state on login, the new day, the phase title and music on a
  phase change; the days stay put with nobody online
  (`pauseWhenEmpty`) and go on when it is off. Prints `RESULT fails=N`.
- `worldgen`: generates `size` x `size` new chunks at chunk `genX`, `genZ` (forced,
  `day`, `active=false` for before the peak) and prints the time and CPU it took
  (`gen`), the work after it (`process`, `processTicks` ticks, `sample=true` adds
  `SAMPLE` lines: where the server thread spends its time) and what the chunks hold
  (`GENCOUNT`, `GENORES`; `dumpOres=true` writes every ore of the inner area to
  `<out_name>.ores`).
- `watercheck`: the water removal of new chunks against the code it replaced (copied
  in the harness), on the same new ocean and land chunks: blocks and heightmaps must
  be the same (`WATERCHECK ... diffs=0`), with the time of both.
- `retrofit`: on a world an older version generated after the peak (a `worldgen` run
  of that version, same `genX`, `genZ`, `size`), the chunks are seen again: their
  ores must come back (`RETRO before/after`, `.ores` dump to compare with a
  `worldgen dumpOres=true` run of the new code), except in 3x3 chunks given a long
  inhabited time (`touchX`, `touchZ`) and in a chunk with a chest (`chestX`,
  `chestZ`). `only=even` sees half of them (keep the world and run again: the others
  must get theirs after the restart). The world's config must be past the peak
  (`RETRO retrofit=true`): a world never played past it by an older version keeps
  the retrofit off (`worldgen` prints `GENRETROFIT`, false for a new world).
- `logic`: checks of the new behaviour (steps, restart, ritual, old configs,
  light sync, animal spawning, breeding, mobs left in unattended areas, heights
  after days skipped by hand, snow and ice melting, no fire after the end).
  Prints PASS/FAIL lines and `RESULT fails=N`.

Every run ends with `FALLING joined=N`, the falling block entities created.

`ClientProbe.java` is for the dev client (`gradlew runClient`, copy it into the
same `selftest` folder): while `run/probe.flag` exists it logs every 5 seconds a
`[PROBE]` line with the columns around the player that have less than full sky
light above their top block, as the client sees them.

Use `PORT=<port>` to run several servers at once (results that depend on the
first ticks after loading can then vary under load; repeat the run).
