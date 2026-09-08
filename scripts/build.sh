#!/usr/bin/env bash
# 骨架可构建入口 —— 发卷平台准入门（bagu-trainer 设计 §4.6）里「骨架可构建」这一项
# 的稳定调用点，track.yaml 的 runtime.build 指向这里。
#
# 改动本文件后必须重算 digest_pin（runtime 的每个可执行入口都要被 pin，§4.6）：
#   bash scripts/digest-pin.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# -B：batch 模式，关掉 ANSI 进度条，CI 日志可读。
exec mvn -B -DskipTests compile
