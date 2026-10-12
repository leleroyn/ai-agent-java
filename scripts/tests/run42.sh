set -e
cd /data/ai-agent
cp .env .env.bak42
grep -vE '^AGENT_PDF_SSRF_ALLOWLIST=|^AGENT_IPS_OCR_TIMEOUT=|^AGENT_TOOL_SHELL=|^AGENT_SHELL_DENIED=' .env.bak42 > .env.new
cat >> .env.new <<EOF
AGENT_PDF_SSRF_ALLOWLIST=192.168.2.203
AGENT_IPS_OCR_TIMEOUT=2
AGENT_TOOL_SHELL=true
AGENT_SHELL_DENIED=curl,wget
EOF
mv .env.new .env
docker rm -f ai-agent-java >/dev/null 2>&1 || true
bash scripts/docker-run.sh >/dev/null 2>&1 || true
sleep 10
echo "== env =="; docker exec ai-agent-java env | grep -E 'SSRF|IPS_OCR_TIMEOUT'
API=http://localhost:8080/api/v1/agent/task
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf

echo "== A: disallowed host must be rejected at ingest (agent must not start) =="
cat > c42-ssrf.json <<EOF
{"taskId":"s42-ssrf","instruction":"识别 http://127.0.0.1:8090/files/test/examples/seal_1.jpg 的印章，把印章上的公司名称放入键\"公司\"。最终答案只输出一个 JSON 对象。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":120}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c42-ssrf.json > r42-ssrf.json
python3 -c "import json;d=json.load(open('r42-ssrf.json'))['data'];print('A',d['status'],d.get('durationMs'),json.dumps(d.get('error'),ensure_ascii=False)[:300])"

echo "== B: allowed host still works =="
cat > c42-ok.json <<EOF
{"taskId":"s42-ok","instruction":"识别 $SEAL 的印章，把印章上的公司名称放入键\"公司\"。最终答案只输出一个 JSON 对象。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":120}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c42-ok.json > r42-ok.json
python3 -c "import json;d=json.load(open('r42-ok.json'))['data'];print('B',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:200])"

echo "== C: forced OCR page failures -> partial =="
cat > c42-partial.json <<EOF
{"taskId":"s42-partial","instruction":"读取 $P72（72 页），抽取字段：合同名称、甲方、乙方、合同金额。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":true,"timeoutSeconds":420}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c42-partial.json > r42-partial.json
python3 -c "import json;d=json.load(open('r42-partial.json'))['data'];print('C',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:400])"

echo "== logs =="
docker logs ai-agent-java --since 15m 2>&1 | grep -oE "ocr->txt pdf src=[^ ]* pages=[0-9/]+ failed=\[[^]]*\][^,]*|file=[^ ]*-partial.txt|partial_note|不在允许的白名单内：[0-9.]+|reuse txt" | sort | uniq -c | tail -12

echo "== restore env =="
mv .env.bak42 .env
docker rm -f ai-agent-java >/dev/null 2>&1 || true
bash scripts/docker-run.sh >/dev/null 2>&1 || true
sleep 6
curl -s -o /dev/null -w "health=%{http_code}\n" http://localhost:8080/actuator/health