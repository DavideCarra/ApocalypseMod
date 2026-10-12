#!/bin/bash
# Runs one self test scenario on a dedicated server (see README.md).
# usage: tools/selftest/runtest.sh <project_dir> <fresh|keep|copy:<world_dir>> <out_name> key=value ...
set -e
P=$(cd "$1" && pwd); MODE=$2; OUT=$3; shift 3
H=$(cd "$(dirname "$0")" && pwd)
T=$P/src/main/java/com/creatormc/judgementdaymod/selftest
mkdir -p "$T"
cp "$H/SelfTestCommon.java" "$T/"
# The checks of the new behaviour only compile against the new code
if [ -f "$P/src/main/java/com/creatormc/judgementdaymod/handlers/UnattendedChunkHandler.java" ]; then
  cp "$H/SelfTestNew.java" "$T/"
fi
R=$P/run
mkdir -p "$R/config"
echo "eula=true" > "$R/eula.txt"
cat > "$R/server.properties" <<EOP
online-mode=false
level-seed=${SEED:-1234567}
level-name=world
view-distance=4
simulation-distance=4
spawn-protection=0
max-tick-time=-1
sync-chunk-writes=false
server-port=${PORT:-25565}
EOP
cat > "$R/config/judgementdaymod-apocalypse.properties" <<EOP
apocalypseActive=false
apocalypseCurrentDay=0
apocalypseMaxDays=50
apocalypseEndDay=200
minDamageHeight=60
minWaterEvaporationHeight=60
startBeforeApocalypse=false
EOP
case "$MODE" in
  fresh) rm -rf "${R:?}/world" ;;
  copy:*) rm -rf "${R:?}/world"; cp -r "${MODE#copy:}" "$R/world" ;;
esac
: > "$R/selftest.properties"
echo "output=$OUT" >> "$R/selftest.properties"
for kv in "$@"; do echo "$kv" >> "$R/selftest.properties"; done
rm -f "${R:?}/${OUT:?}"
cd "$P"
timeout 1500 bash ./gradlew runServer --no-daemon -q > "$R/gradle-$OUT.log" 2>&1 || true
rm -f "$R/selftest.properties"
if [ -f "$R/$OUT" ]; then
  echo "OUTPUT $R/$OUT"
  grep -E "EXCEPTION|PASS|FAIL|RESULT|LIGHT|TOTAL|REGION" "$R/$OUT" | head -80
else
  echo "NO OUTPUT"; tail -30 "$R/gradle-$OUT.log"
fi
