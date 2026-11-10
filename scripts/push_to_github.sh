#!/usr/bin/env bash
# K-Reader → GitHub 推送腳本
# 權杖只走環境變數 GITHUB_TOKEN：不寫入任何檔案、不寫入遠端 URL，用後即棄。
#
# 用法：
#   GITHUB_TOKEN=<你的PAT> bash scripts/push_to_github.sh https://github.com/<帳號>/<repo>.git
#
# PAT 建議：細粒度權杖（Fine-grained），
#   建立頁：https://github.com/settings/personal-access-tokens/new
#   權限：Repository access → 僅選目標 repo；Permissions → Contents: Read and write
#   效期：7 天即可；推送完成後請到同頁撤銷。

set -euo pipefail

REPO_DIR="/home/z/my-project"
REMOTE_URL="${1:?用法: GITHUB_TOKEN=<PAT> bash scripts/push_to_github.sh https://github.com/<帳號>/<repo>.git}"

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "錯誤：未提供 GITHUB_TOKEN 環境變數" >&2
  exit 1
fi

# GIT_ASKPASS：git 每次詢問帳密時執行此腳本；
# 腳本內容只有「echo "$GITHUB_TOKEN"」，權杖值本身絕不落地。
ASKPASS="$(mktemp /tmp/kreader_askpass.XXXXXX)"
trap 'rm -f "$ASKPASS"' EXIT
printf '#!/usr/bin/env bash\necho "$GITHUB_TOKEN"\n' > "$ASKPASS"
chmod 700 "$ASKPASS"

echo "→ 推送 main 分支（$(git -C "$REPO_DIR" rev-list --count main) 個提交）至 $REMOTE_URL ..."
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$ASKPASS" \
  git -C "$REPO_DIR" push "$REMOTE_URL" main:main

# 推送成功後，把 origin 指向乾淨 URL（不含權杖），方便日後直接 git push
if git -C "$REPO_DIR" remote | grep -qx origin; then
  git -C "$REPO_DIR" remote set-url origin "$REMOTE_URL"
else
  git -C "$REPO_DIR" remote add origin "$REMOTE_URL"
fi

# 建立上游追蹤（日後 git push / git pull 免帶 URL）
GIT_TERMINAL_PROMPT=0 GIT_ASKPASS="$ASKPASS" \
  git -C "$REPO_DIR" fetch origin --quiet
git -C "$REPO_DIR" branch --set-upstream-to=origin/main main

echo "✓ 推送完成；origin 已設為 $REMOTE_URL"
echo "✓ 提醒：請到 GitHub Settings → Developer settings 撤銷剛才使用的 PAT"
