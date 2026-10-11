#!/bin/bash
set -e
Q="SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='jt_resource' AND TABLE_NAME='agent_task' AND COLUMN_NAME='sync_requested';"
echo "== before =="
docker run --rm mysql:5.7 mysql -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -N -e "$Q" 2>/dev/null
if [ -z "$(docker run --rm mysql:5.7 mysql -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -N -e "$Q" 2>/dev/null)" ]; then
  echo "== applying migration-005 =="
  docker run --rm mysql:5.7 mysql -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -e "ALTER TABLE agent_task ADD COLUMN sync_requested TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'sync priority' AFTER model_name;" 2>/dev/null
else
  echo "== already present, skip =="
fi
echo "== after =="
docker run --rm mysql:5.7 mysql -h192.168.2.190 -P3306 -uroot -p123456 jt_resource -e "SHOW COLUMNS FROM agent_task LIKE 'sync_requested';" 2>/dev/null