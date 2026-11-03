#!/bin/bash
# ffmpeg の ebur128 フィルターで、レンダリングした音の聴感上の音量（LUFS）と音量の幅（LRA）を測る
# usage: ./loudness.sh a.wav b.wav ...
for f in "$@"; do
  r=$(ffmpeg -nostats -i "$f" -af ebur128=peak=true -f null - 2>&1 | grep -A12 "Summary:" | tr '\n' ' ')
  I=$(echo "$r" | sed -n 's/.*I: *\(-\?[0-9.]*\) LUFS.*/\1/p'); L=$(echo "$r" | sed -n 's/.*LRA: *\([0-9.]*\) LU.*/\1/p'); P=$(echo "$r" | sed -n 's/.*Peak: *\(-\?[0-9.]*\) dBFS.*/\1/p')
  printf "%-34s integrated %7s LUFS   LRA %5s LU   true-peak %7s dBFS\n" "$(basename $f)" "$I" "$L" "$P"
done
