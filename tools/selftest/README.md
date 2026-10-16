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
  swamp) and saves the world. Copy `run/world` somewhere and pass it with
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
- `clientlight`: logs in a fake player with a recording connection, lets the
  mod process the chunks it sees (`phaseChangeDay` simulates a phase change
  afterwards) and rebuilds the client light from the packets it received,
  compared with the server light.
- `perf`: the `clientlight` run, measured. For each window (`join`: the
  player arrives, `lightTicks` ticks; `phase`: after `phaseChangeDay`;
  `idle`: `idleTicks` more ticks) it prints the server tick times (harness
  time excluded), the time of the mod's end of tick work, the CPU time and
  allocations of every thread group (`main` server thread, `worker` light and
  world generation, `io`) and the packets sent to the player with their size,
  raw and compressed as on a dedicated server. `realistic=true` leaves fire,
  random ticks and the rest of the world running (only time, weather and
  natural spawning are frozen). If the mod is instrumented with a public
  `Generator.PROF` array it is printed too (`PROF` lines). Run it on both
  versions, one after the other (never two at once), and repeat: single runs
  vary by 10-20%.
- `logic`: checks of the new behaviour (steps, restart, ritual, old configs,
  light sync, animal spawning, breeding, mobs left in unattended areas).
  Prints PASS/FAIL lines and `RESULT fails=N`.

Use `PORT=<port>` to run several servers at once (results that depend on the
first ticks after loading can then vary under load; repeat the run).
