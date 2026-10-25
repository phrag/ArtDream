#!/usr/bin/env bash
# Send a video (or picture) to ArtDream's "My media" on your TV. For a YouTube link, the
# video is downloaded here first with yt-dlp, so nothing YouTube-specific runs on the TV.
#
#   scripts/yt-to-tv.sh <tv-address> <youtube-url>
#   scripts/yt-to-tv.sh <tv-address> --file <video-or-picture>
#
# <tv-address> is what Settings > My media shows on the TV, e.g. 192.168.2.115:8765/k3m9x2qp
# Needs: curl, and for YouTube links yt-dlp (macOS: brew install yt-dlp). The TV and this
# computer must be on the same network, with the My media screen open on the TV.
#
# Please respect the rights of the people who made what you download. YouTube's terms
# don't allow downloading except through YouTube's own features; this is your call to make.
set -euo pipefail

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-2}"; }

[[ $# -ge 2 ]] || usage
addr=${1#http://}; addr=${addr%/}; shift
[[ $addr =~ ^[A-Za-z0-9._-]+:[0-9]+/[a-z0-9]+$ ]] || {
  echo "That doesn't look like the TV's address (expected like 192.168.2.115:8765/k3m9x2qp)." >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is needed." >&2; exit 1; }

urlencode() {
  local LC_ALL=C s=$1 i c h out=
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case $c in
      [a-zA-Z0-9.~_-]) out+=$c ;;
      *) printf -v h '%%%02X' "'$c"; out+=$h ;;
    esac
  done
  printf '%s' "$out"
}

send() {  # send <file>
  local file=$1 name resp
  name=$(basename "$file")
  echo "Sending $name ($(du -h "$file" | cut -f1)) to the TV..."
  resp=$(curl -sS --connect-timeout 5 -T "$file" "http://$addr/upload?name=$(urlencode "$name")" -w '\n%{http_code}') || {
    echo "Couldn't send to the TV. Is Settings > My media open on it, and is this the address it shows now?" >&2
    echo "(The address changes when the ArtDream app restarts.)" >&2
    return 1; }
  local code=${resp##*$'\n'} body=${resp%$'\n'*}
  if [[ $code == 200 ]]; then
    echo "Added."
  else
    echo "The TV said no ($code): $body" >&2
    return 1
  fi
}

if [[ $1 == --file ]]; then
  [[ $# -eq 2 && -f $2 ]] || usage
  send "$2"
  exit
fi

[[ $# -eq 1 ]] || usage
url=$1
command -v yt-dlp >/dev/null || { echo "yt-dlp isn't installed. On macOS: brew install yt-dlp" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
echo "Downloading with yt-dlp (up to 1080p, H.264 so the TV can play it)..."
file=$(yt-dlp --no-playlist --restrict-filenames --windows-filenames \
  -f 'bv*[height<=1080][vcodec^=avc1]+ba[acodec^=mp4a]/b[height<=1080][vcodec^=avc1]/b[height<=1080]' \
  --merge-output-format mp4 -o "$tmp/%(title).80B.%(ext)s" \
  --print after_move:filepath --no-simulate "$url" | tail -n 1)
[[ -f $file ]] || { echo "yt-dlp didn't produce a file." >&2; exit 1; }
send "$file"
