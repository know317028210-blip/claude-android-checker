#!/usr/bin/env bash
# ============================================================
#  Claude Code 环境检测器 (claude-env-detector)
#  一条龙扫描本机环境，输出不符合 Claude Code 要求/建议的项
#  用法:  bash claude-env-check.sh [--json]
#  平台:  Windows (Git Bash) / macOS / Linux
# ============================================================
set -uo pipefail

JSON_MODE=0
[[ "${1:-}" == "--json" ]] && JSON_MODE=1

# ---------- 统计 ----------
PASS=0; WARN=0; FAIL=0
declare -a ISSUES=()

# 颜色
if [[ -t 1 && $JSON_MODE -eq 0 ]]; then
  C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_B=$'\033[36m'; C_0=$'\033[0m'; C_DIM=$'\033[2m'
else
  C_G=""; C_Y=""; C_R=""; C_B=""; C_0=""; C_DIM=""
fi

section() { [[ $JSON_MODE -eq 0 ]] && printf "\n%s━━ %s ━━%s\n" "$C_B" "$1" "$C_0"; }

# result <status: pass|warn|fail> <name> <detail>
result() {
  local st=$1 name=$2 detail=${3:-}
  case $st in
    pass) PASS=$((PASS+1)); [[ $JSON_MODE -eq 0 ]] && printf "  [%s✅%s] %-28s %s\n" "$C_G" "$C_0" "$name" "$detail" ;;
    warn) WARN=$((WARN+1)); ISSUES+=("WARN|$name|$detail"); [[ $JSON_MODE -eq 0 ]] && printf "  [%s⚠️ %s] %-28s %s\n" "$C_Y" "$C_0" "$name" "$detail" ;;
    fail) FAIL=$((FAIL+1)); ISSUES+=("FAIL|$name|$detail"); [[ $JSON_MODE -eq 0 ]] && printf "  [%s❌%s] %-28s %s\n" "$C_R" "$C_0" "$name" "$detail" ;;
  esac
}

ver_ge() {  # ver_ge 2.1.0 1.0.0 -> 比较语义化版本, $1 >= $2 返回0
  local a b IFS=.
  read -r -a a <<< "$1"; read -r -a b <<< "$2"
  for i in 0 1 2; do
    local x=${a[i]:-0} y=${b[i]:-0}
    x=${x//[!0-9]/}; y=${y//[!0-9]/}
    (( 10#$x > 10#$y )) && return 0
    (( 10#$x < 10#$y )) && return 1
  done
  return 0
}

CLAUDE_DIR="$HOME/.claude"
CLAUDE_JSON="$HOME/.claude.json"

# Windows (Git Bash) 下把 /c/... 路径转成 Node 可识别的 C:/... (正斜杠形式)
if command -v cygpath >/dev/null 2>&1; then
  NP() { cygpath -m "$1"; }
else
  NP() { echo "$1"; }
fi

if [[ $JSON_MODE -eq 0 ]]; then
  printf "%s╔══════════════════════════════════════════╗%s\n" "$C_B" "$C_0"
  printf "%s║   Claude Code 环境检测器                 ║%s\n" "$C_B" "$C_0"
  printf "%s╚══════════════════════════════════════════╝%s\n" "$C_B" "$C_0"
  printf "  扫描时间: %s   平台: %s\n" "$(date '+%Y-%m-%d %H:%M:%S')" "$(uname -s)"
fi

# ============================================================
section "1. 基础运行环境"
# ============================================================

# Node.js —— Claude Code 官方要求 Node 18+
if command -v node >/dev/null 2>&1; then
  NODE_V=$(node -v 2>/dev/null | sed 's/^v//')
  if ver_ge "$NODE_V" 18.0.0; then
    result pass "Node.js" "v$NODE_V (要求 >=18)"
  else
    result fail "Node.js 版本过低" "当前 v$NODE_V，Claude Code 要求 Node >= 18，请升级: https://nodejs.org"
  fi
else
  result fail "Node.js 未安装" "Claude Code 依赖 Node.js >= 18，请安装: https://nodejs.org 或 nvm install 20"
fi

# npm
if command -v npm >/dev/null 2>&1; then
  result pass "npm" "$(npm -v 2>/dev/null)"
else
  result warn "npm 未安装" "随 Node.js 一起安装即可，用于安装 @anthropic-ai/claude-code"
fi

# Git —— Claude Code 大量依赖 git 操作
if command -v git >/dev/null 2>&1; then
  result pass "Git" "$(git --version 2>/dev/null | awk '{print $3}')"
else
  result fail "Git 未安装" "Claude Code 的文件历史/diff 功能依赖 Git，请安装 https://git-scm.com"
fi

# ripgrep (Claude Code 内置, 但独立安装可加速)
command -v rg >/dev/null 2>&1 && result pass "ripgrep (可选)" "$(rg --version | head -1 | awk '{print $2}')" || true

# curl / 网络工具
command -v curl >/dev/null 2>&1 && result pass "curl" "可用" || result warn "curl 未安装" "网络检测将受限"

# 磁盘权限: ~/.claude 可写
if [[ -w "$HOME" ]]; then
  result pass "家目录可写" "$HOME"
else
  result fail "家目录不可写" "无法创建 ~/.claude 配置目录"
fi

# ============================================================
section "2. Claude Code CLI"
# ============================================================

CLAUDE_BIN=""
if command -v claude >/dev/null 2>&1; then
  CLAUDE_BIN=$(command -v claude)
  CLAUDE_VER=$(claude --version 2>/dev/null | awk '{print $NF}')
  if [[ -n "$CLAUDE_VER" ]]; then
    result pass "Claude Code CLI" "$CLAUDE_VER ($CLAUDE_BIN)"
  else
    result warn "Claude Code CLI" "找到 $CLAUDE_BIN 但 --version 无输出，可能损坏，建议重装"
  fi
else
  result fail "Claude Code 未安装" "执行: npm install -g @anthropic-ai/claude-code  (或官方 native 安装脚本)"
fi

# 配置目录
if [[ -d "$CLAUDE_DIR" ]]; then
  result pass "~/.claude 目录" "存在"
else
  [[ -n "$CLAUDE_BIN" ]] && result warn "~/.claude 目录" "不存在，首次运行 claude 时会自动创建" || result warn "~/.claude 目录" "不存在 (未安装 Claude Code)"
fi

# ============================================================
section "3. 认证与 API 配置"
# ============================================================

HAS_AUTH=0
settings_file="$CLAUDE_DIR/settings.json"
creds_file="$CLAUDE_DIR/.credentials.json"

# settings.json 存在性与 JSON 合法性
if [[ -f "$settings_file" ]]; then
  if node -e "JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$settings_file")"),'utf8'))" 2>/dev/null; then
    result pass "settings.json" "存在且 JSON 格式合法"
  else
    result fail "settings.json 格式错误" "$settings_file JSON 解析失败，Claude Code 将无法读取配置"
  fi
else
  result warn "settings.json 不存在" "首次运行 claude /login 后自动生成"
fi

# OAuth 凭据文件
if [[ -f "$creds_file" ]]; then
  HAS_AUTH=1
  result pass "OAuth 凭据" ".credentials.json 存在 (已登录)"
elif [[ -f "$CLAUDE_JSON" ]] && node -e "
  const c=JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$CLAUDE_JSON")"),'utf8'));
  process.exit(c.oauthAccount?0:1)" 2>/dev/null; then
  HAS_AUTH=1
  result pass "OAuth 凭据" "~/.claude.json 中有登录账号信息"
fi

# API Key 环境变量
if [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
  HAS_AUTH=1
  result pass "ANTHROPIC_API_KEY" "已设置 (${ANTHROPIC_API_KEY:0:7}...)"
else
  result warn "ANTHROPIC_API_KEY 未设置" "使用 API 方式才需要；订阅用户走 /login OAuth 即可"
fi

if [[ -n "${ANTHROPIC_AUTH_TOKEN:-}" ]]; then
  HAS_AUTH=1
  result pass "ANTHROPIC_AUTH_TOKEN" "已设置 (中转/代理鉴权)"
fi

# Base URL —— 检测第三方中转 / 被篡改
if [[ -n "${ANTHROPIC_BASE_URL:-}" ]]; then
  case "$ANTHROPIC_BASE_URL" in
    https://api.anthropic.com*|https://api.anthropic.com) result pass "ANTHROPIC_BASE_URL" "官方地址" ;;
    *)
      HAS_AUTH=1
      result warn "ANTHROPIC_BASE_URL 指向第三方" "$ANTHROPIC_BASE_URL —— 若非你本人配置，API Token 可能被劫持，请立即检查并轮换密钥 (安全风险)"
      ;;
  esac
fi

# settings.json 内的 env 注入 (投毒检测)
if [[ -f "$settings_file" ]]; then
  SUS_URL=$(node -e "
    try{
      const s=JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$settings_file")"),'utf8'));
      const e=(s.env||{}).ANTHROPIC_BASE_URL||'';
      console.log(e);
    }catch(err){}" 2>/dev/null)
  if [[ -n "$SUS_URL" ]]; then
    case "$SUS_URL" in
      https://api.anthropic.com*) result pass "settings.json 中的 BASE_URL" "官方地址" ;;
      *) result warn "settings.json 注入了中转地址" "$SUS_URL —— 检查是否本人配置，防提示词注入篡改网关 (AGENTS.md 投毒常见手法)" ;;
    esac
  fi
  # 危险权限
  DANGEROUS=$(node -e "
    try{
      const s=JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$settings_file")"),'utf8'));
      const p=JSON.stringify(s.permissions||{});
      if(p.includes('--dangerously')||p.includes('bypassPermissions')) console.log('yes');
    }catch(err){}" 2>/dev/null)
  [[ "$DANGEROUS" == "yes" ]] && result warn "危险权限配置" "settings.json 中存在 bypassPermissions / dangerously 相关授权，建议收紧" || result pass "权限配置" "未发现危险的全局放行"
fi

if [[ $HAS_AUTH -eq 0 ]]; then
  result fail "无任何认证方式" "既没有登录凭据也没有 API Key —— 运行 claude 后执行 /login，或设置 ANTHROPIC_API_KEY"
fi

# ============================================================
section "4. MCP 与插件"
# ============================================================

if [[ -f "$CLAUDE_JSON" ]]; then
  MCP_COUNT=$(node -e "
    try{
      const c=JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$CLAUDE_JSON")"),'utf8'));
      console.log(Object.keys(c.mcpServers||{}).length);
    }catch(err){console.log('ERR')}" 2>/dev/null)
  if [[ "$MCP_COUNT" == "ERR" ]]; then
    result fail "~/.claude.json 损坏" "JSON 解析失败，建议备份后删除让其重建"
  elif [[ "$MCP_COUNT" -gt 0 ]]; then
    result pass "MCP 服务器" "已配置 $MCP_COUNT 个 (全局)"
  else
    result warn "MCP 服务器" "未配置任何全局 MCP (可选功能)"
  fi
else
  result warn "~/.claude.json" "不存在 (未运行过 Claude Code)"
fi

# ============================================================
section "5. 网络连通性"
# ============================================================

if command -v curl >/dev/null 2>&1; then
  HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 8 -m 12 https://api.anthropic.com/v1/models 2>/dev/null)
  case "$HTTP_CODE" in
    401|403|400) result pass "api.anthropic.com 可达" "HTTP $HTTP_CODE (网络通，未带鉴权返回 401 属正常)" ;;
    000) result fail "api.anthropic.com 不可达" "连接超时/被墙 —— 需配置代理 (HTTPS_PROXY) 或使用官方支持的中转地址" ;;
    *) result warn "api.anthropic.com" "HTTP $HTTP_CODE，异常响应，检查代理/防火墙" ;;
  esac

  NPM_CODE=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 8 -m 12 https://registry.npmjs.org 2>/dev/null)
  [[ "$NPM_CODE" == "200" || "$NPM_CODE" == "301" ]] && result pass "npm registry 可达" "HTTP $NPM_CODE" || result warn "npm registry 不可达" "HTTP $NPM_CODE —— 影响 claude-code 安装/升级"

  # 代理环境变量
  if [[ -n "${HTTPS_PROXY:-}${HTTP_PROXY:-}${https_proxy:-}${http_proxy:-}" ]]; then
    result pass "代理已配置" "HTTPS_PROXY=${HTTPS_PROXY:-${https_proxy:-}}"
  else
    result warn "未检测到代理环境变量" "若你在网络受限地区直连失败，需要设置 HTTPS_PROXY"
  fi
fi

# ============================================================
section "6. 安全风险扫描"
# ============================================================

# 下载/桌面目录的诱饵指令文件 (AGENTS.md 投毒)
for f in "$HOME/Downloads/AGENTS.md" "$HOME/Downloads/CLAUDE.md" "$HOME/Desktop/AGENTS.md" "$HOME/Desktop/CLAUDE.md"; do
  [[ -f "$f" ]] && result warn "发现可疑指令文件" "$f —— 来历不明的 AGENTS.md/CLAUDE.md 会被 Agent 当作指令执行，务必人工审查"
done
result pass "诱饵文件扫描" "下载/桌面目录未见 AGENTS.md / CLAUDE.md" || true

# 项目内的 .claude/settings.json 是否注入异常 base url
PROJ_SETTINGS="$PWD/.claude/settings.json"
if [[ -f "$PROJ_SETTINGS" ]]; then
  P_URL=$(node -e "
    try{
      const s=JSON.parse(require('fs').readFileSync($(printf "'%s'" "$(NP "$PROJ_SETTINGS")"),'utf8'));
      console.log((s.env||{}).ANTHROPIC_BASE_URL||'');
    }catch(err){}" 2>/dev/null)
  if [[ -n "$P_URL" && "$P_URL" != https://api.anthropic.com* ]]; then
    result warn "项目级 BASE_URL 疑似被篡改" "$PROJ_SETTINGS 注入了 $P_URL —— 若非本人配置立即删除并轮换 Token"
  else
    result pass "项目级 settings" "无异常 env 注入"
  fi
else
  result pass "项目级 settings" "当前目录无 .claude/settings.json"
fi

# 时区/语言 (与账号地区一致性，风控参考项)
TZ_NOW=$(date +%Z)
[[ $JSON_MODE -eq 0 ]] && printf "  ${C_DIM}ℹ️  系统时区: %s | 语言: %s${C_0}\n" "$(date '+%Z %z')" "${LANG:-unknown}"

# ============================================================
# 汇总
# ============================================================

if [[ $JSON_MODE -eq 1 ]]; then
  node -e "
  const issues = $(printf '%s\n' "${ISSUES[@]:-}" | node -e "
    let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{
      const rows=d.trim()?d.trim().split('\n'):[];
      console.log(JSON.stringify(rows.map(r=>{const [lv,n,...rest]=r.split('|');return {level:lv,name:n,detail:rest.join('|')}})));
    })");
  console.log(JSON.stringify({pass:$PASS,warn:$WARN,fail:$FAIL,issues},null,2))"
else
  printf "\n%s━━ 扫描完成 ━━%s\n" "$C_B" "$C_0"
  printf "  通过: %s✅ %d%s   警告: %s⚠️  %d%s   失败: %s❌ %d%s\n" "$C_G" "$PASS" "$C_0" "$C_Y" "$WARN" "$C_0" "$C_R" "$FAIL" "$C_0"
  if (( FAIL + WARN > 0 )); then
    printf "\n%s需要处理的问题:%s\n" "$C_Y" "$C_0"
    local_i=0
    for item in "${ISSUES[@]:-}"; do
      lv=${item%%|*}; rest=${item#*|}
      name=${rest%%|*}; detail=${rest#*|}
      if [[ "$lv" == "FAIL" ]]; then
        printf "  %s❌%s %s\n      %s→ %s%s\n" "$C_R" "$C_0" "$name" "$C_DIM" "$detail" "$C_0"
      else
        printf "  %s⚠️ %s %s\n      %s→ %s%s\n" "$C_Y" "$C_0" "$name" "$C_DIM" "$detail" "$C_0"
      fi
    done
  else
    printf "\n%s🎉 环境完全就绪，可以放心使用 Claude Code！%s\n" "$C_G" "$C_0"
  fi
  # 退出码: 有 fail 为 1
  (( FAIL > 0 )) && exit 1 || exit 0
fi
