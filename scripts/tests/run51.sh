#!/usr/bin/env bash
# 两条通道（异步 async-concurrency / 同步 sync-concurrency）的回归（DESIGN §7）。
# 前提：AGENT_ASYNC_CONCURRENCY=1、AGENT_SYNC_CONCURRENCY=1（默认值）。
# 每次换 taskId 后缀，避免命令行幂等重放掩盖真实行为。
set -e
cd /data/ai-agent
P2=http://192.168.2.203:8090/files/test/20250724/e99176566e2a4241b23920b03d67922d.pdf
P72=http://192.168.2.203:8090/files/test/20260920/0914625f63c247c2865a88f0df5dc658.pdf
SUF=$(date +%H%M%S)
API=http://localhost:8080/api/v1/agent/task
HEALTH=http://localhost:8080/api/v1/agent/health
DB() { docker run --rm mysql:5.7 mysql -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -N -e "$1" 2>/dev/null; }
H() { curl -s $HEALTH | python3 -c "import json,sys;d=json.load(sys.stdin)['data'];print({k:d.get(k) for k in ('active','queued','queuedSync','syncActive','syncConcurrency','asyncConcurrency')})"; }

submit() { # $1=id $2=instruction $3=sync(true/false) $4=timeout
  python3 - "$1" "$2" "$3" "$4" <<PY
import json,sys
json.dump({"taskId":sys.argv[1],"instruction":sys.argv[2],
 "options":{"sync":sys.argv[3]=="true","timeoutSeconds":int(sys.argv[4])}},
 open("c51-"+sys.argv[1]+".json","w"))
PY
  curl -s -X POST $API -H "Content-Type: application/json" --data @c51-$1.json > r51-$1.json
  python3 -c "import json;d=json.load(open('r51-$1.json'))['data'];print('$1',d['status'],d.get('durationMs'))"
}
EX72="读取 $P72（72 页施工合同），抽取字段：合同名称、甲方、乙方、合同金额、签订日期。最终答案只输出一个 JSON 对象，键就是上面的字段名，值为字符串，找不到的用 null。不要输出任何解释、前后缀或 Markdown 代码围栏。"
EX2="用 extract_document_fields 从 $P2 抽取字段：合同名称、甲方、乙方。最终答案只输出一个 JSON 对象，键就是上面的字段名。不要输出任何解释、前后缀或 Markdown 代码围栏。"

echo "== S0 health 新字段（queuedSync / syncWorkers / syncConcurrency）=="
H

echo "== S1 主验收：异步通道被 72 页长任务占满时，同步任务不被堵住 =="
submit a72-$SUF "$EX72" false 600 &
sleep 8
submit s2p-$SUF "$EX2" true 180
wait
echo "-- 期望：s2p 的 durationMs 在 30s 内（旧行为要等 72 页跑完才轮到它）--"
DB "SELECT task_id,status,TIMESTAMPDIFF(SECOND,created_at,started_at) AS wait_s FROM agent_task WHERE task_id='s2p-$SUF';"
echo "-- wait_s 应为 0：submit() 提交完行就 notifyAll()，入队到开跑不等轮询 --"

echo "== S2 lane=1：同时最多 1 个同步在跑（每秒采样）=="
for n in 1 2 3; do submit sy$n-$SUF "$EX2" true 180 & done
MAX=0
for i in $(seq 1 45); do
  C=$(DB "SELECT COUNT(*) FROM agent_task WHERE status='running' AND sync_requested=1;")
  [ "${C:-0}" -gt "$MAX" ] && MAX=$C
  sleep 1
done
wait
echo "max concurrent sync = $MAX  (期望 1)"
DB "SELECT task_id,status,TIMESTAMPDIFF(SECOND,created_at,started_at) AS wait_s FROM agent_task WHERE task_id LIKE 'sy%-$SUF' ORDER BY started_at;"

echo "== S3 通道隔离：同步行只由 lane worker 认领 =="
for id in s2p-$SUF sy1-$SUF sy2-$SUF sy3-$SUF; do
  if docker logs ai-agent-java --since 20m 2>&1 | grep -aq "sync lane claimed task $id"; then
    echo "$id lane=OK"
  else
    echo "$id lane=MISSING"
  fi
done
docker logs ai-agent-java --since 20m 2>&1 | grep -aE "sync lane (opened|closing)" | tail -6

echo "== S4 常驻 + 信号唤醒：空闲查询只是 3s 兜底，本地提交立刻醒 "
docker logs ai-agent-java --since 40m 2>&1 | grep -aE "task poller started" | tail -1
H
A=$(DB "SHOW GLOBAL STATUS LIKE 'Com_select';" | awk '{print $2}')
sleep 30
B=$(DB "SHOW GLOBAL STATUS LIKE 'Com_select';" | awk '{print $2}')
echo "30s 全局 SELECT 增量 = $((B-A))（这个库是共享的，只能当上限参考；两条常驻 worker 自身约 20 条）"
echo "-- 期望 health：syncConcurrency=1、syncActive=0 --"

echo "== S5 同步通道上的 cancel 生效 =="
submit c72-$SUF "$EX72" true 600 &
sleep 25
curl -s -X DELETE $API/c72-$SUF > /dev/null
wait || true
curl -s $API/c72-$SUF | python3 -c "import json,sys;d=json.load(sys.stdin)['data'];print('c72',d['status'])"
docker logs ai-agent-java --since 5m 2>&1 | grep -aE "cancelled \(localInterrupt=" | tail -2

echo "== S5b lane=0 回退（需改 .env 后 bash scripts/docker-run.sh 重启，再单独跑这段）=="
echo "  AGENT_SYNC_CONCURRENCY=0 后：同步行应被异步线程认领（候选排序 sync_requested DESC），"
echo "  health 里 syncConcurrency=0、syncWorkers 恒为 0，且同步行不会没人处理。"
echo "  断言：docker logs ai-agent-java | grep -c 'sync lane' 应为 0，同时 sync 任务仍 completed。"
