#!/bin/zsh
# threadcpu.sh <device> [seconds]: CPU per thread of Glassio over an interval (ticks of 10 ms)
D=$1; S=${2:-10}
P=$(adb -s $D shell pidof com.glasslauncher.tv)
snap() { adb -s $D shell "for t in /proc/$P/task/*; do echo \$(basename \$t) \$(cat \$t/stat 2>/dev/null | sed 's/.*) //' | awk '{print \$12+\$13}') \$(cat \$t/comm); done"; }
snap > /tmp/tc1; sleep $S; snap > /tmp/tc2
join <(sort /tmp/tc1) <(sort /tmp/tc2) | awk '{d=$4-$2; if (d>0) print d, $3}' | sort -rn | head -15
