set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
BAD=http://192.168.2.203:8090/files/test/nope-missing-404.pdf
PAGE=http://192.168.2.203:8090/admin/list
API=http://localhost:8080/api/v1/agent/task
WS=/data/ai-agent/agent-workspace

run() {
  local id="s47-$1"
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
json.dump(body,open("c47-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c47-$1.json > r47-$1.json
  python3 -c "import json;d=json.load(open('r47-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:300])"
}

echo "== T1 mixed task: extract + seal, each URL must be fetched exactly once =="
run mix "用 extract_document_fields 从 $P2 抽取字段：合同名称、甲方、乙方；再用 seal-recognition 技能从 $SEAL 识别印章，取 seals[].ocr_result。最终答案只输出一个 JSON 对象，键为 合同名称/甲方/乙方/公司。" 300
echo "-- ingest lines for s47-mix --"
docker logs ai-agent-java --since 10m 2>&1 | grep -a "ingest ok task=s47-mix" || echo "NO INGEST LOG"
echo "-- src dir --"
ls -l $WS/s47-mix/src

echo "== T2 same URL twice in one instruction -> exactly one local file =="
run dup "读取 $P2 和 $P2（同一份文件），抽取字段：合同名称、甲方。最终答案只输出一个 JSON 对象。" 300
echo "-- src file count (expect 1) --"
ls $WS/s47-dup/src | wc -l
cat $WS/s47-dup/.ingest/manifest.json

echo "== T3 unreachable file -> SOURCE_UNAVAILABLE, agent must not start =="
run bad "读取 $BAD，抽取字段：合同名称。最终答案只输出一个 JSON 对象。" 120
echo "-- tool logs for s47-bad (expect none) --"
docker logs ai-agent-java --since 5m 2>&1 | grep -a "task=s47-bad\|s47-bad" | grep -a "doc extract\|recognize_\|ocr->txt" || echo "no tool activity (good)"

echo "== T4 model forced to pass a URL -> tool rejects it, task still finishes =="
run urlsrc "读取 $P2 抽取字段：合同名称、甲方。注意：调用 extract_document_fields 时 source 参数必须写成 $P2 这个完整 URL，不要用系统给的路径。最终答案只输出一个 JSON 对象。" 300
echo "-- rejection log --"
docker logs ai-agent-java --since 5m 2>&1 | grep -a "工具不接受 URL" | tail -3 || echo "no rejection seen"

echo "== T5 non-file URL in instruction stays untouched =="
run page "参考页面 $PAGE 无关内容。读取 $P2，抽取字段：合同名称。最终答案只输出一个 JSON 对象。" 300
echo "-- ingest lines (expect exactly 1) --"
docker logs ai-agent-java --since 5m 2>&1 | grep -a "ingest ok task=s47-page" | wc -l

echo "== T6 72-page scan regression =="
python3 - <<PY
import json
json.dump({"taskId":"s47-ex72","instruction":"读取 $P72（72 页施工合同），抽取字段：合同名称、甲方、乙方、合同金额、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。","options":{"sync":False,"timeoutSeconds":600}},open("c47-ex72.json","w"))
PY
curl -s -X POST $API -H "Content-Type: application/json" --data @c47-ex72.json > /dev/null
for i in $(seq 1 60); do sleep 10; curl -s "$API/s47-ex72" | grep -qE '"status":"(completed|failed)"' && break; echo "poll $i"; done
curl -s "$API/s47-ex72" > r47-ex72.json
python3 -c "import json;d=json.load(open('r47-ex72.json'))['data'];print('ex72',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:400])"
echo "-- ingest + ocr lines for s47-ex72 (expect exactly one ingest, one ocr->txt) --"
docker logs ai-agent-java --since 20m 2>&1 | grep -aE "ingest ok task=s47-ex72|ocr->txt|doc extract sources" | tail -8

echo "== cleanup: delete-workspace-on-finish=false keeps everything =="
ls -a $WS/s47-mix
