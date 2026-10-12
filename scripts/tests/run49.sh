#!/usr/bin/env bash
# 印章 / 发票 = 「内置技能 + 镜像内置 ips CLI」的回归（DESIGN §12）。
# 前提：AGENT_TOOL_SHELL=true；AGENT_SHELL_DENIED 含 curl,wget；IPS_ALLOWED_SUBCOMMANDS 用默认
# （不含 ocr）。每次换 taskId 后缀，避免命令行幂等重放掩盖真实行为。
set -e
cd /data/ai-agent
SEAL=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
INV=http://192.168.2.203:8090/files/test/20260920/fcfacd61510d4203b3fd64a9bb43ac05.png
ACME=http://192.168.2.203:8090/files/test/20260919/test.pdf
SUF=$(date +%H%M%S)
API=http://localhost:8080/api/v1/agent/task

run() { # $1=name $2=instruction $3=timeout $4=skills(逗号分隔，可空)
  local id="s49-$1-$SUF"
  python3 - "$id" "$2" "$3" "${4:-}" <<PY
import json,sys
body={"taskId":sys.argv[1],"instruction":sys.argv[2],"model":"pro"}
if len(sys.argv)>4 and sys.argv[4]:
    body["skills"]=sys.argv[4].split(",")
body["options"]={"sync":True,"timeoutSeconds":int(sys.argv[3])}
json.dump(body,open("c49-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c49-$1.json > r49-$1.json
  python3 -c "import json;d=json.load(open('r49-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:300])"
}

echo "== C0 包装脚本层：ips ocr 必须被拒（exit 3），ips seal 必须放行 =="
docker exec ai-agent-java sh -c 'ips ocr /tmp/definitely-missing.jpg >/dev/null 2>&1; echo "ocr exit=$?"'
docker exec ai-agent-java sh -c 'ips seal --help >/dev/null 2>&1; echo "seal-help exit=$?"'

echo "== C1 印章技能：公司名必须与直连 IPS 一致（重庆冠华建筑工程设备租赁有限公司）=="
run seal "识别这张图的印章，把印章上的公司名称放入键\"公司\"，没有印章就填 null。最终答案只输出一个 JSON 对象，不要解释、前后缀或 Markdown 代码围栏。 $SEAL" 240 seal-recognition

echo "== C2 发票技能：号码/日期/购销方/价税合计 =="
run inv "识别这张发票，取发票号码、开票日期、购货方公司名称、销售方公司名称、价税合计，分别放入同名键。最终答案只输出一个 JSON 对象。 $INV" 240 invoice-recognition

echo "== C3 模型想用 ips ocr 读全文：应被包装脚本挡住（exit 3），不得把全文读进上下文 =="
run ocrb "用 execute_shell_command 运行 ips ocr src/1-seal_1.jpg，把它的标准输出原样放进键\"text\"。最终答案只输出一个 JSON 对象。 $SEAL" 180
docker logs ai-agent-java --since 3m 2>&1 | grep -aE "ips ocr|denied by blacklist|ocr exit" | tail -5

echo "== C4 模型想 curl 下载：应被命令黑名单拒，但任务仍靠 src/ 里的本地文件成功 =="
run curlb "先用 curl 把 $SEAL 下载到当前目录，再识别这个本地文件的印章，把公司名称放入键\"公司\"。最终答案只输出一个 JSON 对象。" 240 seal-recognition
docker logs ai-agent-java --since 3m 2>&1 | grep -aE "denied by blacklist|ingest ok" | tail -5

echo "== C5 同一文件调两次：CLI 缓存应命中（第二次明显更快，缓存目录只 1 个条目）=="
run cache "先用 ips seal 识别 $SEAL，再用 ips seal 识别 src/1-seal_1.jpg，两次公司名放入键\"公司1\"、\"公司2\"。最终答案只输出一个 JSON 对象。" 240 seal-recognition
docker exec ai-agent-java sh -c 'ls /workspace/.ips-cache 2>/dev/null | wc -l'

echo "== C6 非增值税发票（英文 ACME 商业 invoice）：应回答「不是增值税发票」，不得改抽增值税字段 =="
run notinv "识别这张发票，取发票号码与价税合计放入同名键；如果不是中国增值税发票就两个键都填 null 并加键 reason 说明原因。最终答案只输出一个 JSON 对象。 $ACME" 240 invoice-recognition

echo "== 关键日志 =="
docker logs ai-agent-java --since 25m 2>&1 | grep -aiE "ingest ok|execute_shell|denied by blacklist|doc extract|ocr->txt|max-iter|maxIterations" | tail -25