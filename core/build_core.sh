#!/bin/bash
set -e
echo "Building Go Core for Android..."
gomobile bind -target=android -androidapi 21 -ldflags="-s -w" -o ../app/libs/libcore.aar .
echo "Finished: app/libs/libcore.aar"
