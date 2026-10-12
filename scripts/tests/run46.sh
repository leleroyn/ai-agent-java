set -e
cd /data/ai-agent
# 固定 taskId 会命中上一轮的幂等重放（实测踩过：跑出来的全是昨天的记录）。
SUF=-$(date +%H%M%S)
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
BAD=http://192.168.2.203:8090/files/test/nope-missing-404.jpg
API=http://localhost:8080/api/v1/agent/task

run() {
  local id="s46-$1$SUF"
  # 指令里提到印章/发票就自动挂上对应技能：这两项已从内置工具改为「技能 + 镜像内置 ips CLI」。
  local skills=""
  case "$2" in *印章*) skills="${skills:+$skills,}seal-recognition";; esac
  case "$2" in *发票*) skills="${skills:+$skills,}invoice-recognition";; esac
  python3 - "$id" "$2" "$3" "$skills" <<PY
import json,sys
body={"taskId":sys.argv[1],"instruction":sys.argv[2]}
if len(sys.argv)>4 and sys.argv[4]:
    body["skills"]=sys.argv[4].split(",")
body["options"]={"sync":True,"timeoutSeconds":int(sys.argv[3])}
json.dump(body,open("c46-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c46-$1.json > r46-$1.json
  python3 -c "import json;d=json.load(open('r46-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:200])"
}

echo "== T1 seal small smoke =="
run seal "用 seal-recognition 技能从 $SEAL 识别印章，取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象。" 180

echo "== T2 extract 2p smoke =="
run ex2 "读取 $P2，抽取字段：合同名称、甲方、乙方。最终答案只输出一个 JSON 对象。" 300

echo "== T3 failing source, model retries (failure must count toward cap) =="
run badsrc "用 seal-recognition 技能从 $BAD 识别印章，取 seals[].ocr_result 放入键\"公司\"；如果失败就重试，直到拿到结果。最终答案只输出一个 JSON 对象。" 300

echo "== T4 extract 72p regression =="
python3 - <<PY
import json
json.dump({"taskId":"s46-ex72$SUF","instruction":"读取 $P72（72 页施工合同），抽取字段：合同名称、甲方、乙方、合同金额、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":False,"timeoutSeconds":600}},open("c46-ex72.json","w"))
PY
curl -s -X POST $API -H "Content-Type: application/json" --data @c46-ex72.json > /dev/null
for i in $(seq 1 60); do sleep 10; curl -s "http://localhost:8080/api/v1/agent/task/s46-ex72$SUF" | grep -qE '"status":"(completed|failed)"' && break; echo "poll $i"; done
curl -s "http://localhost:8080/api/v1/agent/task/s46-ex72$SUF" > r46-ex72.json
python3 -c "import json;d=json.load(open('r46-ex72.json'))['data'];print('ex72',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:400])"

echo "== logs =="
docker logs ai-agent-java --since 25m 2>&1 | grep -aiE "idempotent hit|refused after|execute_shell|doc extract sources|ocr->txt" > /tmp/l46.txt
cat /tmp/l46.txt