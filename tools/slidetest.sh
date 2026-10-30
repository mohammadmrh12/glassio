#!/bin/zsh
# slidetest.sh <art_choice> : steps through slides, prints screen hash (changes = picture shown) and Java heap
D=${TV:?set TV=<tv-ip:5555>}
adb -s $D shell am start -n com.glasslauncher.tv/.MainActivity --es dev "$(adb -s $D shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)" --es art_choice $1 >/dev/null
sleep 3; adb -s $D shell input keyevent 19
for i in $(seq 1 ${2:-10}); do
  adb -s $D shell input keyevent 22; sleep 1.5
  h=$(adb -s $D exec-out screencap -p | shasum | cut -c1-6)
  m=$(adb -s $D shell 'dumpsys meminfo com.glasslauncher.tv | grep -E "Dalvik Heap|Native Heap  " | head -2' | awk '{printf "%s=%d ", $1, $8/1024}')
  echo "$1 step$i bg=$h ${m}MB"
  sleep 2.5
done
