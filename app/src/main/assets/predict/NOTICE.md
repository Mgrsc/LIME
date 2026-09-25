# System prediction data notice

`system_predict.tsv` is generated from the `predict.txt` asset in the official
`rime/librime-predict` `data-1.0` release.

- Release: https://github.com/rime/librime-predict/releases/tag/data-1.0
- Source SHA-256: `df0f7a9ef96569da402d9ea2376aefad4d15382ebcccb05ec84a0acbc00c7f83`
- Generator: `tools/generate_system_predict_asset.sh`
- Filter parameters: `--thresh-2char=12000 --thresh-multi=2500 --max-candidates=6`
- Generated asset SHA-256: `6b2a4ea2e2ccc96462db37967934a63a4ed0649b5729c95ae79a36b1df635e10`

The release describes this database as made from `rime-essay` and octagram.
`rime-essay` is LGPL-3.0; its license is included at
`predict/LICENSES/rime-essay-LGPL-3.0.txt`. The `librime-predict` plugin itself
is BSD-3-Clause. The release does not state an independent license for the
aggregated prediction data, so downstream redistribution must retain this
notice and recheck the upstream data lineage. This project does not claim
ownership of the source data.

Generation also applies the reviewed exclusions in `tools/dictionary/prediction-exclusions.tsv`.
These bounded corrections do not establish corpus-wide semantic quality.
