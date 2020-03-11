#!/bin/bash
# (always ratchet threshold upwards)
lein cloverage --fail-threshold 78 --html
