#!/usr/bin/env bash
set -euo pipefail

echo "== Wisp Nobitex AI Trader — server setup =="

if ! command -v docker &> /dev/null; then
  echo "Docker not found — installing..."
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER" || true
  echo "Docker installed. You may need to log out and back in for group changes to apply."
fi

if [ ! -f .env ]; then
  echo "No .env found — copying from .env.example."
  cp .env.example .env
  echo ""
  echo "!!! Edit .env now and fill in your keys, then re-run this script. !!!"
  exit 1
fi

echo "Building and starting the container..."
docker compose up -d --build

echo ""
echo "Done."
echo "Logs:         docker compose logs -f"
echo "Health check: curl http://localhost:10000/healthz"
echo "Stop:         docker compose down"
