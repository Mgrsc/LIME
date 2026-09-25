#!/usr/bin/env bash
set -euo pipefail

input_file=${1:?usage: generate_system_predict_asset.sh <predict.txt> <output.tsv>}
output_file=${2:?usage: generate_system_predict_asset.sh <predict.txt> <output.tsv>}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

opencc_cfg="t2s.json"

mkdir -p "$(dirname "$output_file")"

tmp_file=$(mktemp)
trap 'rm -f "$tmp_file"' EXIT

opencc -c "$opencc_cfg" < "$input_file" | python3 "$script_dir/dictionary/filter_prediction_data.py" > "$tmp_file"

mv "$tmp_file" "$output_file"
