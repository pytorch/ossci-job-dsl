#!/bin/sh

set -e

# Configure maximum (in case it wasn't yet set)
max="${CCACHE_MAX_MB:-50000}"
ccache -o max_size="${max}M"

echo "---- CCACHE CONFIGURATION ----"
ccache -p
echo "---- CCACHE CONFIGURATION ----"

# Check if we should perform cleanup
mb=$(du -sm "$CCACHE_DIR" | awk '{print $1}')
if [ "$mb" -lt "$max" ]; then
  echo "$CCACHE_DIR size is ${mb}M (max. ${max}MB); skipping cleanup"
  exit 0
fi

echo "$CCACHE_DIR size is ${mb}M (max. ${max}MB); running cleanup"
echo "---- BEFORE ----"
ccache -s
ccache -c
echo ""
echo "---- AFTER ----"
ccache -s
