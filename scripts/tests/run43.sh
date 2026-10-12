set -e
cd /data/ai-agent
cp .env .env.bak43
grep -vE '^AGENT_PDF_SSRF_ALLOWLIST=|^AGENT_TOOL_SHELL=|^AGENT_IPS_OCR_TIMEOUT=' .env.bak43 > .env.new
cat >> .env.new <<EOF
AGENT_PDF_SSRF_ALLOWLIST=192.168.2.203
AGENT_TOOL_SHELL=false
AGENT_IPS_OCR_TIMEOUT=1
EOF
mv .env.new .env
docker rm -f ai-agent-java >/dev/null 2>&1 || true
bash scripts/docker-run.sh >/dev/null 2>&1 || true
sleep 10
docker exec ai-agent-java env | grep -E 'SSRF|TOOL_SHELL|IPS_OCR_TIMEOUT'
API=http://localhost:8080/api/v1/agent/task
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf

echo "== D: disallowed host rejected at ingest (shell off, agent must not start) =="
cat > c43.json <<EOF
{"taskId":"s43-ssrf","instruction":"识别 http://127.0.0.1:8090/files/test/examples/seal_1.jpg 的印章，把印章上的公司名称放入键\"公司\"。最终答案只输出一个 JSON 对象。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":120}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c43.json > r43.json
python3 -c "import json;d=json.load(open('r43.json'))['data'];print('D',d['status'],d.get('durationMs'),json.dumps(d.get('error'),ensure_ascii=False)[:300])"

echo "== E: OCR timeout=1 -> expect partial =="
cat > c43p.json <<EOF
{"taskId":"s43-partial","instruction":"读取 $P72（72 页），抽取字段：合同名称、甲方、乙方、合同金额。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":true,"timeoutSeconds":420}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c43p.json > r43p.json
python3 -c "import json;d=json.load(open('r43p.json'))['data'];print('E',d['status'],d.get('durationMs'),(d.get('resultText') or d.get('error') or '')[:400])"

echo "== server-side evidence (before restore) =="
docker logs ai-agent-java --since 20m 2>&1 | grep -oE "不在允许的白名单内：[0-9.]+|pages=[0-9]+/[0-9]+ failed=\[[^]]{1,40}\]|file=[^ ]*-partial\.txt|partial_note|OCR \u5df2\u5b8c\u6210\u4f46\u6ca1\u6709\u4ea7\u51fa\u6587\u672c|\u9010\u9875 OCR \u5168\u90e8\u5931\u8d25" | sort | uniq -c | head -10

echo "== restore =="
mv .env.bak43 .env
docker rm -f ai-agent-java >/dev/null 2>&1 || true
bash scripts/docker-run.sh >/dev/null 2>&1 || true
sleep 6
curl -s -o /dev/null -w "health=%{http_code}\n" http://localhost:8080/actuator/health