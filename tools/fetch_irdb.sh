#!/usr/bin/env bash
# DOGS Remote — re-fetch the irdb source CSVs used by tools/gen_ir_db.py.
# Source of truth: https://github.com/probonopd/irdb (branch master).
# Puts the files where gen_ir_db.py expects them (/tmp/irdb by default;
# override with IRDB_DIR env var).
set -euo pipefail
OUT="${IRDB_DIR:-/tmp/irdb}"
BASE="https://raw.githubusercontent.com/probonopd/irdb/master/codes"
mkdir -p "$OUT"
fetch() { curl -fsSL --max-time 60 "$BASE/$1" -o "$OUT/$2"; echo "ok: $1"; }
fetch "Samsung/TV/7,7.csv"            samsung_7_7.csv
fetch "LG/TV/4,-1.csv"                LG_TV_4_-1.csv
fetch "Sony/TV/1,-1.csv"              Sony_TV_1_-1.csv
fetch "TCL/TV/15,-1.csv"             TCL_TV_15_-1.csv
fetch "Philips/TV/0,-1.csv"          Philips_TV_0_-1.csv
fetch "Vizio/Unknown_Vizio/4,-1.csv" Vizio_Unknown_Vizio_4_-1.csv
fetch "Panasonic/TV/128,0.csv"       Panasonic_TV_128_0.csv
fetch "Sharp/TV/1,-1.csv"            Sharp_TV_1_-1.csv
fetch "Toshiba/TV/64,-1.csv"         Toshiba_TV_64_-1.csv
fetch "JVC/TV/3,-1.csv"              JVC_TV_3_-1.csv
fetch "Insignia/TV/134,5.csv"        Insignia_TV_134_5.csv
echo "CSVs in $OUT — now run: python3 tools/gen_ir_db.py"
