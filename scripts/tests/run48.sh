set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
BAD=http://192.168.2.203:8090/files/test/nope-missing-404.pdf
API=http://localhost:8080/api/v1/agent/task
WS=/data/ai-agent/agent-workspace

run() {
  local id="s48-$1"
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
json.dump(body,open("c48-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c48-$1.json > r48-$1.json
  python3 -c "import json;d=json.load(open('r48-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:300])"
}

echo "== S1 mixed extract+seal (one fetch per URL) =="
run mix "用 extract_document_fields 从 $P2 抽取字段：合同名称、甲方；再用 seal-recognition 技能从 $SEAL 识别印章，取 seals[].ocr_result。最终答案只输出一个 JSON 对象，键为 合同名称/甲方/公司。" 300
docker logs ai-agent-java --since 6m 2>&1 | grep -a "ingest ok task=s48-mix" | sed 's/.*SourceIngestor    : //'

echo "== S2 same file with and without a query string -> two entries, no truncation =="
run q "用 seal-recognition 技能分别从 $SEAL 和 $SEAL?v=2 识别印章，取 seals[].ocr_result。最终答案只输出一个 JSON 对象，键为 公司1/公司2。" 300
ls $WS/s48-q/src
docker logs ai-agent-java --since 6m 2>&1 | grep -a "ingest ok task=s48-q" | sed 's/.*SourceIngestor    : //'

echo "== S3 404 file -> SOURCE_UNAVAILABLE =="
run bad "读取 $BAD，抽取字段：合同名称。最终答案只输出一个 JSON 对象。" 120
