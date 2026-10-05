#!/usr/bin/env bash
# Сверяет итоги libancdu_scan.so с GNU du на фикстуре с краевыми случаями.
set -euo pipefail
BIN=${1:?usage: du_compare.sh path/to/libancdu_scan.so}
T=$(mktemp -d)
trap 'chmod -R u+rwx "$T" 2>/dev/null; rm -rf "$T"' EXIT

mkdir -p "$T/a/b/c/d/e/f" "$T/many" "$T/uni/Привет мир" "$T/noperm"
for i in $(seq 1 10000); do printf x > "$T/many/f$i"; done
head -c 123457 /dev/urandom > "$T/a/b/c/d/e/f/blob"
ln "$T/a/b/c/d/e/f/blob" "$T/a/hardlink"
truncate -s 1G "$T/sparse"
ln -s "$T/a" "$T/link_to_a"
printf y > "$T/noperm/hidden"
chmod 000 "$T/noperm"
touch "$T/uni/Привет мир/файл"$'\n'"с переводом" "$T/uni/"$'\xff\xfe'
head -c 5000 /dev/urandom > "$T/uni/Привет мир/data"

want_disk=$( { du -sxB1 "$T" 2>/dev/null || true; } | cut -f1)
want_app=$( { du -sxB1 --apparent-size "$T" 2>/dev/null || true; } | cut -f1)

fail=0
for th in 1 2 8; do
  out=$("$BIN" --summary --root "$T" --threads "$th" || true)
  got_disk=$(awk '$1=="disk"{print $2}' <<<"$out")
  got_app=$(awk '$1=="apparent"{print $2}' <<<"$out")
  if [[ "$got_disk" != "$want_disk" || "$got_app" != "$want_app" ]]; then
    echo "MISMATCH threads=$th: disk $got_disk vs du $want_disk; apparent $got_app vs du $want_app"
    fail=1
  else
    echo "ok threads=$th disk=$got_disk apparent=$got_app"
  fi
done
exit $fail
