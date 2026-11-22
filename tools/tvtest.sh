#!/bin/zsh
# Usage: tools/tvtest.sh [build] KEY[:wait] ... — sends remote keys and saves a 2x2 contact sheet of screenshots.
# "shot" takes a screenshot, "long" holds OK. Output: ~/liquid-shots/grid.png
setopt nullglob
export PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin
D=${TV:?Set TV=<tv-ip>:5555}
cd ~/AI-Home-Control/work/Glass-Launcher
if [[ "$1" == "build" ]]; then
  shift
  : "${JAVA_HOME:?Set JAVA_HOME to a JDK 17}" "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
  ./gradlew :app:assembleGithubRelease --no-daemon --console=plain > /tmp/gb.log 2>&1; grep -E "^e: |BUILD" /tmp/gb.log
  adb connect $D >/dev/null
  adb -s $D install -r app/build/outputs/apk/github/release/app-github-release.apk | tail -1
  adb -s $D shell am force-stop com.glasslauncher.tv
  adb -s $D shell am start -n com.glasslauncher.tv/.MainActivity >/dev/null 2>&1
  sleep 9
fi
mkdir -p ~/liquid-shots; rm -f ~/liquid-shots/t*.png
n=0
for a in "$@"; do
  k=${a%%:*}; w=${a#*:}; [[ "$w" == "$a" ]] && w=1.1
  if [[ "$k" == "shot" ]]; then
    adb -s $D exec-out screencap -p > ~/liquid-shots/t$n.png; sips -Z 960 ~/liquid-shots/t$n.png --out ~/liquid-shots/t$n.png >/dev/null; n=$((n+1))
  elif [[ "$k" == "long" ]]; then
    adb -s $D shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep $w
  else
    adb -s $D shell input keyevent KEYCODE_$k; sleep $w
  fi
done
cd ~/liquid-shots
args=(); for i in $(seq 0 $((n-1))); do args+=(-i t$i.png); done
while (( ${#args[@]}/2 < 4 )); do args+=(-i t0.png); done
ffmpeg -v error -y ${args[@]:0:8} -filter_complex "[0][1]hstack[a];[2][3]hstack[b];[a][b]vstack" grid.png
adb -s $D shell dumpsys window | grep mCurrentFocus | head -1
