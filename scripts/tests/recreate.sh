cd /data/ai-agent
docker rm -f ai-agent-java >/dev/null 2>&1 || true
bash scripts/docker-run.sh 2>&1 | tail -6
docker exec ai-agent-java env | grep -E "AGENT_PDF_TEXT_CHUNK|AGENT_OCR_FORMAT|AGENT_SHELL_ALLOWED"
