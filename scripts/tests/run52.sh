#!/usr/bin/env bash
# 同步调用的 PDF 页窗口：裁剪在文件落盘（ingest）那一刻完成（DESIGN §11）。
# 每次换 taskId 后缀，避免幂等重放。
set -e
cd /data/ai-agent
P72=http://192.168.2.203:8090/files/test/20261013/8dd287e041db467cb6a6f2506b7e9f44.pdf
API=http://localhost:8080/api/v1/agent/task
SUF=$(date +%H%M%S)
DB() { docker run --rm mysql:5.7 mysql --default-character-set=utf8mb4 -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -N -e "$1" 2>/dev/null; }
PAGES() { docker exec ai-agent-java bash -lc "pdfinfo $1 2>/dev/null | grep -i "^Pages""; }

submit() { # $1=id $2=instruction $3=sync $4=timeout
  python3 - "$1" "$2" "$3" "$4" <<PY
import json,sys
json.dump({"taskId":sys.argv[1],"instruction":sys.argv[2],
 "options":{"sync":sys.argv[3]=="true","timeoutSeconds":int(sys.argv[4])}},
 open("c52-"+sys.argv[1]+".json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c52-$1.json > r52-$1.json
  python3 -c "import json;d=json.load(open('r52-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'))"
}
SHOW() { DB "SELECT task_id,status,sync_requested,duration_ms,error_code,LEFT(result_text,300) FROM agent_task WHERE task_id='$1';"; }
LOG() { docker logs ai-agent-java --since 12m 2>&1 | grep -aE "ingest ok|sync window|ocr->txt pdf|doc extract|pdf understand" | tail -8; }

EX="读取 $P72（72 页扫描件），抽取字段：合同金额、合同名称、甲方、乙方、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。"
QA="用 understand_document 读取 $P72，回答：这份合同的签订日期是哪天？合同金额是多少？最终答案只输出一个 JSON 对象，键为 签订日期 和 合同金额。不要输出任何解释或代码围栏。"

echo "== T1 同步 + 72 页抽取：src/ 里的 PDF 应只剩 10 页 =="
submit w-sync-$SUF "$EX" true 300
SHOW w-sync-$SUF
PAGES "/workspace/w-sync-$SUF/src/1-8dd287e041db467cb6a6f2506b7e9f44.pdf"
echo "-- manifest --"
docker exec ai-agent-java cat "/workspace/w-sync-$SUF/.ingest/manifest.json"
echo "-- 分片临时目录应已清掉（src/ 下不该有 win-* ）--"
docker exec ai-agent-java bash -lc "ls -d /workspace/w-sync-$SUF/src/win-* 2>/dev/null || echo no-leftover"
LOG

echo "== T2 异步 + 同一份：src/ 里应仍是 72 页 =="
submit w-async-$SUF "$EX" false 600
echo "-- 提交后立刻看落盘页数（不等跑完）--"
sleep 12
PAGES "/workspace/w-async-$SUF/src/1-8dd287e041db467cb6a6f2506b7e9f44.pdf"

echo "== T3 同步 + understand_document：工具看到的就该是 10 页文件 =="
submit w-qa-$SUF "$QA" true 300
SHOW w-qa-$SUF
PAGES "/workspace/w-qa-$SUF/src/1-8dd287e041db467cb6a6f2506b7e9f44.pdf"
LOG
