set -e
cd /data/ai-agent
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
IMG=http://192.168.2.203:8090/files/RZB/20261010/f992ca9e74314cf0b9c489236409f180.jpg
API=http://localhost:8080/api/v1/agent/task

JSON='最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。'

# 1) 长异步任务先占住唯一 worker（72 页 OCR，约 190s）
cat > c38-long.json <<EOF
{"taskId":"s38-long","instruction":"读取 $P72（72 页），抽取字段：甲方、乙方、合同金额、签订日期。$JSON","options":{"sync":false,"timeoutSeconds":600}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c38-long.json > /dev/null
echo "long submitted $(date +%T)"
sleep 10

# 2) 两个普通异步任务排队（都很快，各约 4-13s）
cat > c38-q1.json <<EOF
{"taskId":"s38-q1","instruction":"用 seal-recognition 技能从 $SEAL 识别印章（不要先下载文件），取 seals[].ocr_result 放入键\"公司\"。$JSON","skills":["seal-recognition"],"options":{"sync":false,"timeoutSeconds":300}}
EOF
cat > c38-q2.json <<EOF
{"taskId":"s38-q2","instruction":"读取图片 $IMG（营业执照），抽取字段：名称、注册资本。$JSON","options":{"sync":false,"timeoutSeconds":300}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c38-q1.json > /dev/null
curl -s -X POST $API -H "Content-Type: application/json" --data @c38-q2.json > /dev/null
echo "q1 q2 submitted $(date +%T)"
sleep 5

# 3) 最后提交的 sync 任务：FIFO 下它排最后，sync 优先下它应排第一
cat > c38-sync.json <<EOF
{"taskId":"s38-sync","instruction":"用 seal-recognition 技能从 $SEAL 识别印章（不要先下载文件），取 seals[].ocr_result 放入键\"公司\"。$JSON","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":300}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c38-sync.json > r38-sync.json &
echo "sync submitted $(date +%T)"

for i in $(seq 1 60); do
  sleep 5; n=0
  for t in long q1 q2; do curl -s "$API/s38-$t" | grep -qE '"status":"(completed|failed)"' && n=$((n+1)); done
  curl -s "$API/s38-sync" | grep -qE '"status":"(completed|failed)"' && n=$((n+1))
  echo "poll $i done=$n $(date +%T)"
  [ "$n" -ge 4 ] && break
done
wait
echo "=== RESULTS ==="
for t in long q1 q2 sync; do
  echo "--- s38-$t ---"
  curl -s "$API/s38-$t" > r38-$t.json
  python3 - <<PY
import json
d=json.load(open("r38-$t.json"))["data"]
print("status",d["status"],"ms",d.get("durationMs"))
print("result",(d.get("resultText") or d.get("error") or "")[:300])
PY
done
