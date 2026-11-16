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
  (`pauseWhenEmpty`) and go on when it is off; the sun heats a player at the bottom
  of a shaft open to the sky below the damage height, not one in a closed room at
  the same depth. Prints `RESULT fails=N`.
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

Comparing with the Forge 1.20.1 version (another Minecraft version: internal block ids
differ): `canonical=true` hashes block states by name and properties in the dumps,
`settleTicks=200` lets the world run that long before the processing (the ticks saved with
the chunks run meanwhile, as soon as their entities are loaded, which takes longer on 1.21),
`dumpBefore=true` dumps the regions just before the processing too (`BEFORE` lines: the input
must be the same). Run the same scenario on a world generated by the Forge version with both
(the Forge harness needs the same three options), then compare the sections whose `BEFORE`
lines are the same. `worlddiff.py` compares two saved worlds block by block and
`effectdiff.py` what two loaders changed in them (control run against processed run).

Every run ends with `FALLING joined=N`, the falling block entities created.

`ClientProbe.java` is for the dev client (`gradlew runClient`, copy it into the
same `selftest` folder): while `run/probe.flag` exists it logs every 5 seconds a
`[PROBE]` line with the columns around the player that have less than full sky
light above their top block, as the client sees them.

Use `PORT=<port>` to run several servers at once (results that depend on the
first ticks after loading can then vary under load; repeat the run).

## Single player (`single`)

The other scenarios run on a dedicated server. `scenario=single` runs in the dev client
(`gradlew runClient`): `SelfTestSingle.java` in the integrated server of a single player
world and `SelfTestClient.java` in the real client of the same process, so the checks
compare what the server has with what the client really has and draws. `runsingle.py`
prepares everything and starts the game straight into the world:

```
tools/selftest/runsingle.py <project_dir> <base_world_dir> <out_dir> [--harness DIR] [--display :99] [--timeout SECONDS]
```

`<base_world_dir>` is a world saved by a dedicated server of the mod (for a comparison between
loaders: the same world, generated by the Forge version). It is copied twice into `run/saves`
(first world: day 9 of 50; second world: day 45 of 80). The script needs an X display (a
virtual one is fine: `Xvfb :99 -screen 0 1280x720x24`), `xdotool` and ImageMagick's `import`:
the scenario asks it for real mouse clicks and for screenshots through the file
`run/sp.request` (`click 3`, `shot <name>`).

What it checks (PASS/FAIL lines, `RESULT1` for the first world and `RESULT` at the end):

- first world: the integrated server has the apocalypse generator and the world's own config
  (`saves/<world>/serverconfig`); the state the client draws is the server's at login; a new
  day by the clock (dawn) starts the next phase: heights, title on the client, music; the game
  is paused (nothing runs on the server: ticks, time of day, day, heat) and, while both worlds
  stand still, every block of the client's chunks around the player is compared with the
  server's, after the chunks were processed for the new step under the client's eyes; the sun
  heats the player and the heat bar has the server's value; death and respawn reset the heat
  on both sides; with real clicks: a block is placed, a water bucket is poured before 40%, the
  first ritual needs its whole pattern and gives one heart, the heart does nothing on stone
  and ends the apocalypse on a grass block (title of the end, config, client state); the
  worlds are compared again at the end.
- the world is left as the Save and Quit to Title button does: what the client draws is reset.
- second world, opened in the same game session: it has its own config (nothing left from the
  first one), the client has its state, a water bucket evaporates at 56% (real click, the
  client has no water and an empty bucket), the worlds are compared; then the game is quit.

The light of the two worlds is compared too, value by value, and reported in the `LIGHT`
lines without a PASS or FAIL: `differ` values are not the same, `insideSolidBlocks` of them are
inside blocks that hide their own light, `seen` are the others (how many brighter on the
client, the largest difference, how many by each difference, in which blocks). It is not
always 0 where chunks were processed under the client's eyes: seen so far are values one level
apart under water after an evaporation or next to flowing lava, and fires that glow on the
client only (the fires lit by the processing do not glow on the server, by design). Compare
the numbers between versions; they change from one run to the next.

The `HEAT ... joining the second world` line is the heat the server still has for the player
from the first world (the heat is kept by player id for the whole game session).
