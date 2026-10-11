set -e
cd /data/ai-agent
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
API=http://localhost:8080/api/v1/agent/task

cat > c39-qa72.json <<EOF
{"taskId":"s39-qa72","instruction":"读取 $P72（72 页），用 understand_document 回答：这份合同的付款条件是什么？违约责任怎么约定？最终答案只输出一个 JSON 对象 {\"付款条件\":\"...\",\"违约责任\":\"...\"}，不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":600}}
EOF
cat > c39-mix.json <<EOF
{"taskId":"s39-mix","instruction":"读取 $P2：先用 extract_document_fields 抽取字段 协议编号、甲方（受让人）；再用 understand_document 用一句话说明这份协议转让的是什么权利。最终答案只输出一个 JSON 对象：{\"协议编号\":\"...\",\"甲方（受让人）\":\"...\",\"标的\":\"...\"}。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":300}}
EOF
cat > c39-a.json <<EOF
{"taskId":"s39-a","instruction":"读取 $P2（应收账款转让登记协议，2 页），抽取字段：协议编号、乙方（出让人）、乙方电子证书号、甲方（受让人）。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":300}}
EOF
for f in qa72 mix a; do curl -s -X POST $API -H "Content-Type: application/json" --data @c39-$f.json > /dev/null; done
for i in $(seq 1 70); do
  sleep 10; n=0
  for f in qa72 mix a; do curl -s "$API/s39-$f" | grep -qE '"status":"(completed|failed)"' && n=$((n+1)); done
  echo "poll $i done=$n $(date +%T)"
  [ "$n" -ge 3 ] && break
done
echo "=== RESULTS ==="
for f in qa72 mix a; do
  echo "--- s39-$f ---"
  curl -s "$API/s39-$f" > r39-$f.json
  python3 - <<PY
import json
d=json.load(open("r39-$f.json"))["data"]
print("status",d["status"],"ms",d.get("durationMs"))
r=d.get("resultText") or d.get("error") or ""
print("result",(r if isinstance(r,str) else json.dumps(r,ensure_ascii=False))[:900])
PY
done