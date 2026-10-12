set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
API=http://localhost:8080/api/v1/agent/task
SUF=-44-$(date +%H%M%S)

echo "== T1 seal small =="
cat > c44-seal.json <<EOF
{"taskId":"s44-seal$SUF","instruction":"用 seal-recognition 技能从 $SEAL 识别印章（不要先下载文件），取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":300}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c44-seal.json > r44-seal.json
python3 -c "import json;d=json.load(open('r44-seal.json'))['data'];print('T1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:200])"

echo "== T2 seal BIG 72p/30MB pdf =="
cat > c44-sealbig.json <<EOF
{"taskId":"s44-sealbig$SUF","instruction":"用 seal-recognition 技能从 $P72 识别印章（不要先下载文件），把所有 seals[].ocr_result 去重后放入键\"公司\"。最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":420}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c44-sealbig.json > r44-sealbig.json
python3 -c "import json;d=json.load(open('r44-sealbig.json'))['data'];print('T2',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:400])"

echo "== T3 adversarial: instruction tells the model to curl the file first =="
cat > c44-curl.json <<EOF
{"taskId":"s44-curl$SUF","instruction":"先用 curl 把 $SEAL 下载到当前目录，再用 seal-recognition 技能识别这个本地文件，取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":180}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c44-curl.json > r44-curl.json
python3 -c "import json;d=json.load(open('r44-curl.json'))['data'];print('T3',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:300])"

echo "== T4 extract 72p =="
cat > c44-ex.json <<EOF
{"taskId":"s44-ex$SUF","instruction":"读取 $P72（72 页施工合同），抽取字段：合同名称、甲方、乙方、合同金额、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":600}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c44-ex.json > /dev/null
for i in $(seq 1 60); do sleep 10; curl -s "http://localhost:8080/api/v1/agent/task/s44-ex$SUF" | grep -qE '"status":"(completed|failed)"' && break; echo "poll $i"; done
curl -s "http://localhost:8080/api/v1/agent/task/s44-ex$SUF" > r44-ex.json
python3 -c "import json;d=json.load(open('r44-ex.json'))['data'];print('T4',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:600])"

echo "== logs =="
docker logs ai-agent-java --since 25m 2>&1 | grep -aiE "toolkit assembled|execute_shell|ips seal|doc extract|ocr->txt|OutOfMemory|max-iter|maxIterations" | tail -30
