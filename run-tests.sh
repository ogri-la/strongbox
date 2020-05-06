#!/bin/bash
# (always ratchet threshold upwards)
lein cloverage --fail-threshold 79 --html
