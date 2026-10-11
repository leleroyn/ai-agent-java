#!/bin/bash
cd /data/ai-agent
cp -n .env .env.bak-wsflag || true
if grep -q '^AGENT_DELETE_WORKSPACE_ON_FINISH=' .env; then
  sed -i 's/^AGENT_DELETE_WORKSPACE_ON_FINISH=.*/AGENT_DELETE_WORKSPACE_ON_FINISH=true/' .env
else
  printf 'AGENT_DELETE_WORKSPACE_ON_FINISH=true\n' >> .env
fi
grep -n 'AGENT_DELETE_WORKSPACE_ON_FINISH' .env