set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
API=http://localhost:8080/api/v1/agent/task

echo "== container mem before =="; docker stats --no-stream --format '{{.MemUsage}}' ai-agent-java
( for i in $(seq 1 60); do docker stats --no-stream --format '{{.MemUsage}}'; sleep 5; done > mem41.txt 2>/dev/null ) &
SAMPLER=$!

echo "== T1 seal small (streaming upload) =="
cat > c41-seal-small.json <<EOF
{"taskId":"s41-seal-small","instruction":"用 seal-recognition 技能从 $SEAL 识别印章（不要先下载文件），取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":300}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c41-seal-small.json > r41-seal-small.json
python3 -c "import json;d=json.load(open('r41-seal-small.json'))['data'];print('T1',d['status'],d.get('durationMs'),(d.get('resultText') or d.get('error') or '')[:200])"

echo "== T2 seal BIG 72p/30MB pdf (streaming upload) =="
cat > c41-seal-big.json <<EOF
{"taskId":"s41-seal-big","instruction":"用 seal-recognition 技能从 $P72 识别印章（不要先下载文件），把所有 seals[].ocr_result 去重后放入键\"公司\"。最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":420}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c41-seal-big.json > r41-seal-big.json
python3 -c "import json;d=json.load(open('r41-seal-big.json'))['data'];print('T2',d['status'],d.get('durationMs'),(d.get('resultText') or d.get('error') or '')[:400])"

echo "== T3 extract 72p (OCR path regression) =="
cat > c41-ex.json <<EOF
{"taskId":"s41-ex","instruction":"读取 $P72（72 页施工合同），抽取字段：合同名称、甲方、乙方、合同金额、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":600}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c41-ex.json > /dev/null
for i in $(seq 1 60); do sleep 10; curl -s "http://localhost:8080/api/v1/agent/task/s41-ex" | grep -qE '"status":"(completed|failed)"' && break; echo "poll $i"; done
curl -s "http://localhost:8080/api/v1/agent/task/s41-ex" > r41-ex.json
python3 -c "import json;d=json.load(open('r41-ex.json'))['data'];print('T3',d['status'],d.get('durationMs'),(d.get('resultText') or d.get('error') or '')[:600])"

kill $SAMPLER 2>/dev/null || true
echo "== mem samples (peak) =="; sort -u mem41.txt | tail -6
echo "== logs =="
docker logs ai-agent-java --since 30m 2>&1 | grep -E "recognize|seal ok|doc extract|ocr->txt|mp-|OutOfMemory" | tail -20
