#!/bin/bash
exec /usr/bin/cog --platform=drm "http://127.0.0.1:8477/rotate.html?v=$(date +%s)&dx=70&dy=0&flip=1"
