set -e
cd /data/ai-agent
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf

cat > c37-qa72.json <<EOF
{"taskId":"s37-qa72","instruction":"读取 $P72（72 页施工合同），用 understand_document 回答两个问题：这份合同的付款条件是什么？违约责任是怎么约定的？最终答案只输出一个 JSON 对象：{\"付款条件\":\"...\",\"违约责任\":\"...\"}。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":600}}
EOF
cat > c37-mix.json <<EOF
{"taskId":"s37-mix","instruction":"读取 $P2：先用 extract_document_fields 抽取字段 协议编号、甲方（受让人）；再用 understand_document 用一句话说明这份协议转让的是什么权利。最终答案只输出一个 JSON 对象：{\"协议编号\":\"...\",\"甲方（受让人）\":\"...\",\"标的\":\"...\"}。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":300}}
EOF
cat > c37-a.json <<EOF
{"taskId":"s37-a","instruction":"读取 $P2（应收账款转让登记协议，2 页），抽取字段：协议编号、乙方（出让人）、乙方电子证书号、甲方（受让人）。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":false,"timeoutSeconds":300}}
EOF
for f in qa72 mix a; do curl -s -X POST http://localhost:8080/api/v1/agent/task -H "Content-Type: application/json" --data @c37-$f.json > /dev/null; done
for i in $(seq 1 80); do
  sleep 10; n=0
  for f in qa72 mix a; do curl -s "http://localhost:8080/api/v1/agent/task/s37-$f" | grep -qE '"status":"(completed|failed)"' && n=$((n+1)); done
  echo "poll $i done=$n"
  [ "$n" -ge 3 ] && break
done
echo "=== RESULTS ==="
for f in qa72 mix a; do echo "--- s37-$f ---"; curl -s "http://localhost:8080/api/v1/agent/task/s37-$f" > r37-$f.json; python3 - <<PY
import json
d=json.load(open("r37-$f.json"))["data"]
print("status",d["status"],"ms",d.get("durationMs"))
print("result",(d.get("resultText") or d.get("error") or "")[:1200])
PY
done