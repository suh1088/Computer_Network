#!/usr/bin/env bash
URL="http://localhost:8080/upload/shared.bin"
FILE="sample.bin"
for i in $(seq 1 10); do
 curl.exe -sS -o /dev/null \
 -w "request=${i} status=%{http_code} time=%{time_total}\n" \
 -X PUT \
 -H "Content-Type: application/octet-stream" \
 --data-binary @"$FILE" \
 "$URL" &
done
wait