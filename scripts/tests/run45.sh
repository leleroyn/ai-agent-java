set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
API=http://localhost:8080/api/v1/agent/task

run() { # $1=name $2=instruction $3=timeout
  local id="s45-$1"
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
json.dump(body,open("c45-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c45-$1.json > r45-$1.json
  python3 -c "import json;d=json.load(open('r45-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or d.get('error') or '')[:220])"
}

echo "== T1 seal, instruction asks to verify twice =="
run seal-twice "用 seal-recognition 技能从 $SEAL 识别印章，然后再调用一次同样的识别做核对，取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象。" 180

echo "== T2 extract same fields twice =="
run ex-twice "读取 $P2，抽取字段：合同名称、甲方、乙方、合同金额；抽完后再用同样的字段抽一次核对。最终答案只输出一个 JSON 对象，键就是字段名。" 300

echo "== T3 force 6 seal calls (cap=4) =="
run seal-six "用 seal-recognition 技能从 $SEAL 识别印章，然后连续再识别 5 次做确认，取 seals[].ocr_result 放入键\"公司\"。最终答案只输出一个 JSON 对象。" 240

echo "== call ledgers =="
for d in s45-seal-twice s45-ex-twice s45-seal-six; do
  f=/data/ai-agent/agent-workspace/$d/.ocr/calls.json
  if [ -f "$f" ]; then echo "-- $d ($(wc -c < "$f") bytes)"; python3 -c "import json,sys;r=json.load(open('$f'));print({k:{'tool':v['tool'],'count':v['count'],'result_chars':len(v.get('result',''))} for k,v in r.items()})"; else echo "-- $d NO LEDGER"; fi
done

echo "== logs =="
docker logs ai-agent-java --since 20m 2>&1 | grep -aiE "idempotent hit|refused after|execute_shell|doc extract sources" | tail -20