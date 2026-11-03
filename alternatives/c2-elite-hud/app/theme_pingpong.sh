#!/bin/bash
# theme_pingpong.sh — 截当前图 → 按一次右移 → 截第二张，判断主题状态
set -e
S="adb -s <LAN_IP>:5555"

$S shell screencap -p /sdcard/t1.png
$S pull /sdcard/t1.png /tmp/t1.png
$S shell input keyevent 22
sleep 2
$S shell screencap -p /sdcard/t2.png
$S pull /sdcard/t2.png /tmp/t2.png
ls -la /tmp/t1.png /tmp/t2.png
echo PONG_DONE
