#!/bin/bash
# Build Baritone for Minecraft 1.21.11 in a reproducible way
set -e

echo "Building Baritone for Minecraft 1.21.11..."
cd "$(dirname "$0")"

# Build the Baritone builder image
docker build -f Dockerfile.baritone --target artifact -t baritone-builder:1.21.11 .

# Extract the JAR from the image
docker create --name baritone-temp baritone-builder:1.21.11
docker cp baritone-temp:/baritone-api-forge-1.15.0.jar ./libs/
docker rm baritone-temp

echo "✓ Baritone JAR built and saved to libs/baritone-api-forge-1.15.0.jar"
echo "If you want to pin a published release for reproducible pulls, use:"
echo "  dvc import-url --force https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-forge-1.15.0.jar libs/baritone-api-forge-1.15.0.jar"
