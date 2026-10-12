set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
API=http://localhost:8080/api/v1/agent/task
echo "workspace before:"; ls -1 agent-workspace 2>/dev/null | head -5; echo "(count=$(ls -1 agent-workspace 2>/dev/null | wc -l))"

cat > c40-seal.json <<EOF
{"taskId":"s40-seal","instruction":"用 seal-recognition 技能从 $SEAL 识别印章（不要先下载文件），取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象，不要输出任何解释、前后缀或 Markdown 代码围栏。","skills":["seal-recognition"],"options":{"sync":true,"timeoutSeconds":300}}
EOF
cat > c40-a.json <<EOF
{"taskId":"s40-a","instruction":"读取 $P2（2 页），抽取字段：协议编号、甲方（受让人）。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":true,"timeoutSeconds":300}}
EOF
curl -s -X POST $API -H "Content-Type: application/json" --data @c40-seal.json > r40-seal.json
curl -s -X POST $API -H "Content-Type: application/json" --data @c40-a.json > r40-a.json
python3 - <<'PY'
import json
for f in ("r40-seal.json","r40-a.json"):
    d=json.load(open(f))["data"]
    print(f, d["status"], d.get("durationMs"), (d.get("resultText") or d.get("error") or "")[:160])
PY
echo "workspace after:"; ls -1 agent-workspace 2>/dev/null | head -10; echo "(count=$(ls -1 agent-workspace 2>/dev/null | wc -l))"
echo "leftover s40 dirs:"; ls -1d agent-workspace/s40-* 2>/dev/null || echo "none"
