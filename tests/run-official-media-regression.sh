#!/bin/sh
# 与其他官方QQ回归共享临时目录/依赖查找，运行全部回归。
set -eu
exec python3 "$(dirname "$0")/run-official-regressions.py"
