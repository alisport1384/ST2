#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# دریافت دیتاست‌های بک‌تست GoldPin از بخش Releases
#
# فایل‌های XAUUSD M1 (۱، ۲، ۳، ۱۰ و ۲۰ ساله) داخل مخزن نیستند؛ به‌صورت asset در
# Release با تگ data-v1 منتشر شده‌اند. این اسکریپت آن‌ها را در datasets/ می‌گذارد تا
# تست‌های engine/app و هارنس cli بتوانند اجرا شوند.
#
#   ./tools/fetch_datasets.sh          # هر پنج برش
#   ./tools/fetch_datasets.sh 1y 10y   # فقط برش‌های خواسته‌شده
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

OWNER_REPO="${GOLDPIN_DATA_REPO:-alisport1384/ST2}"
MIRROR_REPO="${GOLDPIN_DATA_MIRROR:-alisport1384/ST1}"
TAG="${GOLDPIN_DATA_TAG:-data-v1}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/datasets"

declare -A SHA=(
  [1y]=15fffd94b9c1970080d6012ff2aa3ae307d18a872381c677e44d16b5fd08db44
  [2y]=873d4ffc765723abfada8ef55fbf8b9bb9c7432125187395972f51af94496e9f
  [3y]=56751049149f3df1ed134fdbc5e667ec57655f445edde5820ef9d9fed6697fcc
  [10y]=c0e5c8d2151605db8d0cf1c75f3f1d6cca0ecc5304bad233f536ffe45d7f7881
  [20y]=ffbc7c1bd8723728714dfc2f476102f7887be3797d0caf187b622aa533c830c9
)

targets=("$@")
[ ${#targets[@]} -eq 0 ] && targets=(1y 2y 3y 10y 20y)
mkdir -p "$DEST"

for tag in "${targets[@]}"; do
  file="goldpin_xauusd_m1_${tag}.csv.gz"
  out="$DEST/$file"
  want="${SHA[$tag]:-}"
  if [ -z "$want" ]; then echo "!! برش ناشناخته: $tag" >&2; exit 2; fi

  if [ -f "$out" ]; then
    got=$(sha256sum "$out" | cut -d' ' -f1)
    if [ "$got" = "$want" ]; then echo "== $file از قبل سالم است"; continue; fi
    echo "== $file خراب/ناقص است، دوباره دانلود می‌شود"
    rm -f "$out"
  fi

  ok=0
  for repo in "$OWNER_REPO" "$MIRROR_REPO"; do
    url="https://github.com/$repo/releases/download/$TAG/$file"
    echo "== دانلود $file  ←  $repo"
    if curl -fL --retry 3 --retry-delay 2 -C - -o "$out" "$url"; then
      got=$(sha256sum "$out" | cut -d' ' -f1)
      if [ "$got" = "$want" ]; then ok=1; break; fi
      echo "!! SHA-256 نامطابق از $repo (${got:0:12}…)" >&2
      rm -f "$out"
    fi
  done

  if [ "$ok" -ne 1 ]; then echo "!! دانلود $file ناموفق بود" >&2; exit 1; fi
  echo "   ✓ SHA-256 تأیید شد"
done

echo "تمام. فایل‌ها در $DEST"
