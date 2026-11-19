#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export GO_EXECUTABLE="${GO_EXECUTABLE:-$PWD/artifacts/toolchains/go/bin/go}"
# Select the pinned toolchain without installing targets for other phone architectures.
export RUSTUP_TOOLCHAIN="${RUSTUP_TOOLCHAIN:-$(sed -n 's/^channel = "\([^"]*\)"/\1/p' rust_adblock/rust-toolchain.toml)}"

exec ./gradlew :app:assembleDebug -PABI_FILTERS=arm64-v8a --console=plain "$@"
