#!/usr/bin/env bash
# 身份证 / 营业执照技能 + shell 逐段白名单的回归（DESIGN §12）。
# 前提：AGENT_TOOL_SHELL=true；AGENT_SHELL_ALLOWED=ips（逐段白名单）；
#       AGENT_SHELL_DENIED 用默认（curl,wget,/opt/agent-cli/native/ips）；IPS_ALLOWED_SUBCOMMANDS 用默认。
# 每次换 taskId 后缀，避免命令行幂等重放掩盖真实行为。
#
# 样本坑（务必知道）：files/test/examples/seal_1.jpg 的内容其实是**一张身份证**（上面盖了公章），
# 所以它同时是 idcard 的正样本和 seal 的样本；营业执照用 RZB 那张。
set -e
cd /data/ai-agent
IDC=http://192.168.2.203:8090/files/test/examples/seal_1.jpg
BIZ=http://192.168.2.203:8090/files/RZB/20261010/f992ca9e74314cf0b9c489236409f180.jpg
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
SUF=$(date +%H%M%S)
API=http://localhost:8080/api/v1/agent/task

run() { # $1=name $2=instruction $3=timeout $4=skills(逗号分隔，可空)
  local id="s50-$1-$SUF"
  python3 - "$id" "$2" "$3" "${4:-}" <<PY
import json,sys
body={"taskId":sys.argv[1],"instruction":sys.argv[2],"model":"pro"}
if len(sys.argv)>4 and sys.argv[4]:
    body["skills"]=sys.argv[4].split(",")
body["options"]={"sync":True,"timeoutSeconds":int(sys.argv[3])}
json.dump(body,open("c50-$1.json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c50-$1.json > r50-$1.json
  python3 -c "import json;d=json.load(open('r50-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'),(d.get('resultText') or json.dumps(d.get('error'),ensure_ascii=False) or '')[:300])"
}

echo "== D0 包装脚本层：idcard/bizlic 默认放行；CLI 自带 --timeout 默认应为 180 =="
docker exec ai-agent-java sh -c 'ips idcard --help 2>&1 | grep -o "default: 180" | head -1'
docker exec ai-agent-java sh -c 'ips bizlic --help 2>&1 | grep -o "default: 180" | head -1'

echo "== D1 营业执照技能：企业名称/信用代码/法定代表人/注册资本（对照直连 CLI）=="
run biz "识别这张营业执照，把企业名称放入键\"企业名称\"、社会统一信用代码放入键\"信用代码\"、法定代表人放入键\"法定代表人\"、注册资本放入键\"注册资本\"。最终答案只输出一个 JSON 对象，不要解释、前后缀或 Markdown 代码围栏。 $BIZ" 240 bizlic-recognition

echo "== D2 身份证技能正样本 =="
run idpos "识别这张身份证，把姓名放入键\"姓名\"、公民身份号码放入键\"公民身份号码\"。最终答案只输出一个 JSON 对象。 $IDC" 240 idcard-recognition

echo "== D3 身份证技能负样本（营业执照不是身份证）：必须 null + 终局结论，不得改用 extract_document_fields =="
run idneg "识别这张身份证，把姓名放入键\"姓名\"、公民身份号码放入键\"公民身份号码\"；不是身份证就两个键都填 null 并加键 reason 说明原因。最终答案只输出一个 JSON 对象。 $BIZ" 240 idcard-recognition

echo "== D4 营业执照来源是 PDF：接口只收图片，应改走 extract_document_fields 且不反复试 ips bizlic =="
run bizpdf "识别这份文件里的营业执照信息，把企业名称放入键\"企业名称\"、社会统一信用代码放入键\"信用代码\"。最终答案只输出一个 JSON 对象。 $P2" 300 bizlic-recognition
docker exec ai-agent-java sh -c "ls /workspace/s50-bizpdf-*/.ocr/*.txt 2>/dev/null | wc -l"

echo "== D5 逐段白名单：管道里的 head 不在名单内应被拒，纯 ips 命令仍放行 =="
run pipe "用 execute_shell_command 运行：ips seal src/1-seal_1.jpg | head -c 200 —— 把标准输出放进键\"text\"，被拒绝就把原因放进键\"error\"。最终答案只输出一个 JSON 对象。 $IDC" 180 seal-recognition
docker logs ai-agent-java --since 3m 2>&1 | grep -aE "denied by whitelist" | tail -3

echo "== D6 绝对路径直调未包装二进制：应被黑名单（native/ips）拒 =="
run native "必须用 execute_shell_command 执行绝对路径命令：/opt/agent-cli/native/ips seal src/1-seal_1.jpg —— 不要用 ips 这个短命令，必须写全路径。把标准输出放进键\"text\"，被拒绝就把原因放进键\"error\"。最终答案只输出一个 JSON 对象。 $IDC" 180 seal-recognition
docker logs ai-agent-java --since 3m 2>&1 | grep -aE "denied by blacklist" | tail -3

echo "== D7 curl 仍被拒，但任务靠 src/ 成功 =="
run curlb "先用 curl 把 $IDC 下载到当前目录，再识别这个本地文件的印章，把公司名称放入键\"公司\"。最终答案只输出一个 JSON 对象。" 240 seal-recognition

echo "== D8 混合任务在白名单下不退化：抽取 + 印章 =="
run mix "用 extract_document_fields 从 $P2 抽取字段：合同名称、甲方、乙方；再用 ips seal 识别 $IDC 的印章，取 ocr_result。最终答案只输出一个 JSON 对象，键为 合同名称/甲方/乙方/公司。" 300 seal-recognition

echo "== 关键日志 =="
docker logs ai-agent-java --since 40m 2>&1 | grep -aiE "ingest ok|denied by (black|white)list|doc extract|ocr->txt|max-iter" | tail -30
