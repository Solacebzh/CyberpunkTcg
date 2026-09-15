#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# DIAGNOSTIC TEMPORAIRE (branche arena uniquement).
# Les journaux de run GitHub Actions ne sont pas téléchargeables depuis
# l'environnement de travail (hôte results-receiver.actions.githubusercontent.com
# injoignable). Ce script republie donc les échecs Surefire sous forme
# d'annotations de check-run, lisibles via l'API GitHub.
# À supprimer dès que le diagnostic est terminé.
# ---------------------------------------------------------------------------
set -u
REPORT_DIR="${1:-target/surefire-reports}"
OUT="$(mktemp)"
trap 'rm -f "$OUT"' EXIT

if [ ! -d "$REPORT_DIR" ]; then
  echo "::error title=ci-report::Aucun rapport Surefire dans ${REPORT_DIR} - compilation KO ?"
  exit 0
fi

{
  echo "== SUMMARY =="
  grep -h "Tests run:" "$REPORT_DIR"/*.txt 2>/dev/null | sed 's/^[[:space:]]*//'
  echo "== FAILURES =="
  for f in "$REPORT_DIR"/*.txt; do
    [ -e "$f" ] || continue
    grep -qE "FAILURE!|ERROR!" "$f" || continue
    echo "---- $(basename "$f" .txt) ----"
    # Nom du test en échec + les 6 premières lignes non-stack de chaque bloc.
    awk '
      /<<< (FAILURE|ERROR)!/ { keep = 6 }
      /^[ \t]+(at|Caused by|\.\.\.)/ { next }
      keep > 0 { print; keep-- }
    ' "$f" | cut -c1-260 | head -80
  done
} > "$OUT" 2>&1

emit() {
  local msg="$1"
  msg="${msg//%/%25}"
  msg="${msg//$'\r'/}"
  msg="${msg//:/%3A}"
  msg="${msg//$'\n'/%0A}"
  printf '::error title=ci-report::%s\n' "$msg"
}

count=0
chunk=""
while IFS= read -r line; do
  line="${line:0:280}"
  if [ $(( ${#chunk} + ${#line} + 1 )) -gt 800 ]; then
    emit "$chunk"
    chunk=""
    count=$(( count + 1 ))
    if [ "$count" -ge 45 ]; then
      emit "rapport tronque a 45 annotations"
      break
    fi
  fi
  chunk="${chunk}${line}
"
done < "$OUT"
[ -n "$chunk" ] && emit "$chunk"
exit 0
