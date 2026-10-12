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
  of every section, the block entities, the falling blocks and the saved
  phases. Run it on two versions of the code (for example with a
  `git worktree` of `main`) and diff the `AFTER` lines. `lightTicks=200` adds a
  server light check (sky light above the surface, light of the fires).
  `againDay` processes again on a later day (must change nothing after the
  end). `burnFiresBetweenDays=true` and `hashFireAsAir=true` compare a
  catch-up with a day by day run.
- `clientlight`: logs in a fake player with a recording connection, lets the
  mod process the chunks it sees (`phaseChangeDay` simulates a phase change
  afterwards) and rebuilds the client light from the packets it received,
  compared with the server light.
- `logic`: checks of the new behaviour (steps, restart, ritual, old configs,
  light sync, animal spawning, breeding, mobs left in unattended areas).
  Prints PASS/FAIL lines and `RESULT fails=N`.

Use `PORT=<port>` to run several servers at once (results that depend on the
first ticks after loading can then vary under load; repeat the run).
