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
tools/selftest/runsingle.py <project_dir> <base_world_dir> <out_dir> [--harness DIR] [--args-format FORMAT] [--display :99] [--timeout SECONDS]
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

The light of the two worlds is compared too, value by value, once the mod's light work is over
(chunks waiting to be sent again, spills and follow-ups), and reported in the `LIGHT` lines:
`differ` values are not the same, `insideSolidBlocks` of them are inside blocks that hide their
own light, `seen` are the others (how many brighter on the client, the largest difference, how
many by each difference, in which blocks). The fires lit by the processing do not glow (by
design), on the client either since 1.3.5: the client must have no block light value higher
than the server's (`no glow on the client ...`; 1.3.4 had thousands in the second world). The
rest is not checked: the client relights the blocks it gets by itself, and its light can be a
level off here and there even where the mod sent no light at all (at login, next to lava in
caves: 0 to 57 values in repeated runs). After the phase change processed under the client's
eyes 1.3.4 had about 3200 sky light values off under water, 1.3.5 0 to about 100. Compare the
numbers between versions; they change from one run to the next.

Since 1.3.5 the second world starts without the heat of the first one (`HEAT ... joining the
second world`: none); 1.3.4 kept it by player id for the whole game session.

## The Forge version as the reference (`forge-reference/`)

The Forge 1.20.1 project is not changed for these comparisons. `forge-reference/` holds the two
harness files of the Forge version that differ from the ones in its `tools/selftest`:
`SelfTestCommon.java` (the options `canonical`, `settleTicks` and `dumpBefore`, the `single`
scenario) and `SelfTestClient.java`. For a dedicated server scenario, copy the Forge project's
`tools/selftest` somewhere, put `SelfTestCommon.java` in the copy and use the `runtest.sh` of
the copy. For the single player scenario:

```
tools/selftest/runsingle.py <forge_project_dir> <base_world_dir> <out_dir> --harness tools/selftest/forge-reference --args-format=--args=%s
```

## Real servers and real clients (`real/`)

The scripts of `real/` compare the loaders on what the harness cannot fake: production servers
(the release jars, no harness in them) driven through RCON, and real clients (the dev clients)
that join them, with real mouse clicks and keys (xdotool) and screenshots. `real/realenv.py`
lists the environment variables that say where the three servers and projects are; the Forge
version is the reference, run with the same scripts. One test at a time.

- `moved_world.py`: a world with mobs moved between loaders. `create:<loader>:<day>:<name>[:seen]`
  builds a pen in a forced chunk with a mob for every case of the unattended areas (in the
  open, under a roof, glass or leaves, in water, in a waterlogged block, villager, cat, baby,
  fire immune, fire resistant, invulnerable, mount and rider, armor stand, closed room, bottom
  of a shaft open to the sky, deep underground), two ash blocks and a chest with the mod's
  items, and stops the server (every mob is saved with the day). With `seen` a real client
  looks at the place first, so that the chunk is saved with the steps of that day.
  `strip:<name>:<new>` makes the control world, without the stamps (as saved by a version that
  had none).
  `reload:<loader>:<name>:<day>:<label>[:<end day>]` opens a copy on a loader on a later day
  and prints who is left, by tag and UUID, in the running server and in the saved files, the
  stamps written back, the blocks and the chest. The same line must come out of every loader:
  the mobs in the open are removed when at least one full day of apocalypse lies between the
  stamp and today (and before the end day), nobody otherwise; without stamps the saved step of
  the chunk decides.
- `player_returns.py <loader>:<name>:<day>:<label>[:near]`: the same worlds without their
  forced chunk. A real client joins and watches the pen from afar (`near`: stands on it): the
  mobs come back from disk, the chunk catches up under the player's eyes and the survivors are
  judged again. Who is left is sampled every second for 75 seconds (`at_load`, `timeline`), and
  the columns of the pen are read from the saved world at the end. The steps a chunk missed are
  applied to a random share of its columns, so the shelter of a mob holds or not from one run
  to the next, on every loader; only the step of a day at or past the peak (100%) is always the
  same. For a line that must be equal on every loader: `create:forge:45:<name>:seen`, then
  `<loader>:<name>:52:<label>`. For the days in between compare several runs: the mobs in the
  open, in water and in waterlogged blocks are always removed, the fire immune ones, the riders
  and the ones underground never.
- `real_clicks.py`: `base` generates a world, `<loader>:<day>` runs the click sequence on it
  (day 30: survival, 60%; day 60: creative, after the peak), `compare` prints the results of the
  loaders row by row. Every click is a real right click of the client, aimed with tp: a block
  placed (the aim works); water, powder snow and fish buckets evaporating above the water
  height, a lava bucket not, a water bucket in the off hand, water poured below the height;
  the first ritual with a pattern without its egg, on another block of the pattern, with a
  left click, complete, sneaking, with a block in hand, as a spectator; the second ritual on
  stone, with the heart in the off hand, on grass (config, title screenshot), a bucket after
  the end, the ritual again. It runs without random ticks: with them a grass block with water
  above turns to dirt at a random moment (the last two rows show that on every loader).
