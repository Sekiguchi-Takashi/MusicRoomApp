#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1

MSG="${1:-update}"
GH_USER="Sekiguchi-Takashi"
GH_REPO="MusicRoomApp"
TOKEN="$(git config --global github.token)"

if [ -z "$TOKEN" ]; then
  printf '%s\n' "github.token が未設定です: git config --global github.token <TOKEN>"
  exit 1
fi

curl -s -o /dev/null -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d "{\"name\":\"$GH_REPO\",\"private\":true}"

if [ ! -d .git ]; then
  git init -b main
fi

git config user.name "$GH_USER"
git config user.email "$GH_USER@users.noreply.github.com"
git remote remove origin 2>/dev/null
git remote add origin "https://$TOKEN@github.com/$GH_USER/$GH_REPO.git"

git add -A
git commit -m "$MSG" || true
git push -u origin main --force

printf '%s\n' "pushed: $GH_REPO ($MSG)"
printf '%s\n' "APK: https://github.com/$GH_USER/$GH_REPO/releases"
