#!/bin/zsh
# soak.sh <art_choice> <minutes>: autoplay + random browsing, heap every 20 s
D=${TV:?set TV=<tv-ip:5555>}
adb -s $D shell am start -n com.glasslauncher.tv/.MainActivity --es dev "$(adb -s $D shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)" --es art_choice $1 >/dev/null 2>&1
end=$(( $(date +%s) + $2 * 60 ))
while [ $(date +%s) -lt $end ]; do
  for t in 1 2; do adb -s $D shell dumpsys window | grep -m1 mCurrentFocus | grep -q glasslauncher && break; adb -s $D shell input keyevent 4; sleep 2; done
  adb -s $D shell 'input keyevent 19; sleep 0.3; for i in 1 2 3 4; do input keyevent 22; sleep 0.8; done; input keyevent 21; sleep 0.8'
  m=$(adb -s $D shell 'dumpsys meminfo com.glasslauncher.tv | grep -E "Dalvik Heap|Native Heap  |TOTAL PSS" | head -3' | awk '/Heap/{printf "%s=%dMB ", $1, $8/1024} /TOTAL PSS/{printf "PSS=%dMB", $3/1024}')
  echo "$(date +%T) $1 $m"
  sleep 14
done
