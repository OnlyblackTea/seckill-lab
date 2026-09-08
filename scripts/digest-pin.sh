#!/usr/bin/env bash
# 重算 track.yaml 的 runtime.digest_pin —— 发卷平台把 track 当「一组会在学员设备上
# 执行任意代码的供应链包」，所以每个可执行入口都必须被 digest 锁定（设计 §4.2/§4.6/§13.2）。
# pin 手填必然腐烂（改一个文件就过期），故由本脚本生成。
#
# 统一摘要配方（只此一种规则，不分单文件/目录，免得记不住）：
#   1) 把入口解析成一组文件（目录递归、glob 展开）；
#   2) 每个文件先做 CRLF→LF 归一化，再取 sha256；
#   3) 按相对路径 C 序排序，拼成 "<文件 sha256>  <相对路径>\n"；
#   4) 对拼接结果再取一次 sha256，即该入口的 pin（Merkle 式树摘要：增/删/改任一文件都会变）。
#
# 为什么必须归一化：本仓 core.autocrlf=true，在 Windows 上重新 clone 会把 LF 变 CRLF；
# 不归一化的话同一份内容在不同机器上算出不同 pin，pin 就失去意义（.gitattributes 已钉 eol=lf，
# 这里的归一化是第二道保险）。
#
# 用法： bash scripts/digest-pin.sh     # 打印 digest_pin 块，人工核对后粘进 track.yaml
# 入口路径从 track.yaml 的 runtime 段自解析、镜像 ref 从 docker/docker-compose.yml 自解析，
# 所以本脚本不需要跟 track.yaml 保持两份路径清单。
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f track.yaml ] || { echo "找不到 track.yaml（请在仓库根目录执行）" >&2; exit 1; }

RUNTIME_ENTRIES=(compose build tests sim)

file_sha() { tr -d '\r' < "$1" | sha256sum | cut -d' ' -f1; }

entry_files() {  # $1 = 相对路径（可含 glob / 结尾斜杠）；输出排序去重后的文件清单
  local p
  for p in $1; do            # 故意不加引号：让 shell 展开 glob
    p="${p%/}"
    if [ -d "$p" ]; then find "$p" -type f
    elif [ -f "$p" ]; then printf '%s\n' "$p"
    fi
  done | LC_ALL=C sort -u
}

entry_pin() {  # $1 = 入口路径 → sha256:...
  local files
  files="$(entry_files "$1")"
  if [ -z "$files" ]; then
    printf 'sha256:EMPTY-入口没匹配到任何文件-检查 track.yaml 的 runtime\n'
    return 0
  fi
  while IFS= read -r f; do printf '%s  %s\n' "$(file_sha "$f")" "$f"; done <<< "$files" \
    | sha256sum | cut -d' ' -f1 | sed 's/^/sha256:/'
}

runtime_entry() {  # $1 = 入口名 → track.yaml 里 runtime 段声明的相对路径
  # 状态机：只认 `runtime:` 块内、4 空格缩进的直接子键。否则会撞上 manifest.requires.build
  # 这种同名键（踩过：build 被解析成 "maven"）。
  awk -v key="$1" '
    /^  runtime:[[:space:]]*(#.*)?$/ { inrt = 1; next }
    inrt && /^  [^[:space:]]/        { exit }   # 下一个顶层块（contribute: 等）
    inrt && /^    digest_pin:/       { exit }   # pin 块里的同名键不是路径
    inrt && $0 ~ "^    " key ":" {
      sub("^    " key ":[[:space:]]*", "")
      sub("[[:space:]]*#.*$", "")
      gsub(/"/, "")
      sub(/^\.\//, "")
      print
      exit
    }' track.yaml
}

image_pin() {  # $1 = 镜像 ref（mysql:8.0）→ 本机实际拉到的 RepoDigest
  local d=""
  if command -v docker >/dev/null 2>&1; then
    d="$(docker image inspect "$1" --format '{{index .RepoDigests 0}}' 2>/dev/null | sed -n 's/.*@//p')" || true
  fi
  if [ -n "$d" ]; then printf '%s\n' "$d"; return; fi
  printf 'sha256:PENDING-请在跑过 docker compose up 的机器上执行本脚本取值\n'
}

echo "    # 由 scripts/digest-pin.sh 生成（配方见该脚本头部注释）；改完 runtime 相关文件就重跑一次。"
echo "    digest_pin:"
for e in "${RUNTIME_ENTRIES[@]}"; do
  path="$(runtime_entry "$e")"
  [ -n "$path" ] || continue          # 未声明的入口不 pin（§4.6 只要求已声明的入口被覆盖）
  printf '      %-8s "%s"   # %s\n' "$e:" "$(entry_pin "$path")" "$path"
done

while IFS= read -r img; do
  [ -n "$img" ] || continue
  printf '      %-8s "%s"   # %s\n' "${img%%:*}:" "$(image_pin "$img")" "$img"
done < <(tr -d '\r' < docker/docker-compose.yml | sed -nE 's/^[[:space:]]*image:[[:space:]]*"?([^"#]+)"?[[:space:]]*$/\1/p')
