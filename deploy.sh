#!/usr/bin/env bash
#
# Deploys CodeTriage to a remote host over SSH.
#
#   ./deploy.sh              deploy and build
#   ./deploy.sh --logs       deploy, then follow the logs
#   ./deploy.sh --no-build   push files and restart without rebuilding the image
#   ./deploy.sh --logs-only  just follow the remote logs
#   ./deploy.sh --down       stop the remote stack (keeps the database volume)
#
# Override any of these from the environment:
#   DEPLOY_HOST  DEPLOY_USER  DEPLOY_DIR  HOST_PORT  BIND_ADDR
#
# SC2029: the remote commands below interpolate local variables on purpose —
# DEPLOY_DIR and HOST_PORT are this script's settings, not the server's. Each is
# single-quoted inside the remote command so a path with spaces still works.
# shellcheck disable=SC2029

set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-77.37.125.42}"
DEPLOY_USER="${DEPLOY_USER:-root}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/codetriage}"
HOST_PORT="${HOST_PORT:-8089}"
BIND_ADDR="${BIND_ADDR:-0.0.0.0}"

SSH_TARGET="${DEPLOY_USER}@${DEPLOY_HOST}"
SSH_OPTS=(-o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
info()  { printf '  %s\n' "$*"; }
warn()  { printf '\033[33m  ! %s\033[0m\n' "$*"; }
die()   { printf '\033[31m  x %s\033[0m\n' "$*" >&2; exit 1; }

cd "$(dirname "$0")"

MODE="deploy"
FOLLOW_LOGS=false
BUILD=true
for arg in "$@"; do
  case "$arg" in
    --logs)      FOLLOW_LOGS=true ;;
    --logs-only) MODE="logs" ;;
    --no-build)  BUILD=false ;;
    --down)      MODE="down" ;;
    -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
    *)           die "Unknown option: $arg" ;;
  esac
done

# ---------------------------------------------------------------------------
# Modes that do not need the full preflight
# ---------------------------------------------------------------------------
if [ "$MODE" = "logs" ]; then
  exec ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose logs -f --tail=100"
fi

if [ "$MODE" = "down" ]; then
  bold "Stopping the remote stack (the database volume is kept)"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose down"
  info "Stopped. The volume survives; 'docker compose down -v' on the server wipes it."
  exit 0
fi

# ---------------------------------------------------------------------------
# 1. Local preflight
# ---------------------------------------------------------------------------
bold "1/6  Checking locally"

[ -f pom.xml ] || die "No pom.xml here. Run this from the project root."
[ -f docker-compose.yml ] || die "No docker-compose.yml here."
[ -f .env ] || die "No .env here. Copy .env.example to .env and fill it in first."

command -v rsync >/dev/null || die "rsync is not installed."
command -v ssh   >/dev/null || die "ssh is not installed."

# A missing value here produces a container that starts and then cannot be used,
# which is a far more annoying failure than stopping now.
missing=()
for key in ADMIN_USERNAME ADMIN_PASSWORD_HASH; do
  value="$(grep -E "^${key}=" .env | head -1 | cut -d= -f2- || true)"
  [ -n "$value" ] || missing+=("$key")
done
if [ ${#missing[@]} -gt 0 ]; then
  warn "Empty in .env: ${missing[*]}"
  warn "The app will print a temporary password to the log on each restart."
fi
if ! grep -qE "^ANTHROPIC_API_KEY=.+" .env; then
  warn "ANTHROPIC_API_KEY is empty: reference generation and rubric grading will not run."
fi

# Catch the mistake of saving a download over the wrong file.
head -1 docker-compose.yml | grep -q "^services:" \
  || die "docker-compose.yml does not start with 'services:'. Is it the right file?"
head -1 pom.xml | grep -q "xml" \
  || die "pom.xml does not look like XML. Is it the right file?"

info "Project files look sane."

# ---------------------------------------------------------------------------
# 2. Reachability
# ---------------------------------------------------------------------------
bold "2/6  Connecting to ${SSH_TARGET}"

ssh "${SSH_OPTS[@]}" -o BatchMode=yes "$SSH_TARGET" true 2>/dev/null || {
  warn "Key-based login did not work; ssh will prompt for a password."
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" true || die "Cannot reach ${SSH_TARGET}."
}

ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "command -v docker >/dev/null" \
  || die "Docker is not installed on the server. Install it, then re-run."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "docker compose version >/dev/null 2>&1" \
  || die "The docker compose plugin is missing on the server."

REMOTE_MB="$(ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "free -m | awk '/^Mem:/{print \$2}'" 2>/dev/null || echo 0)"
if [ "$BUILD" = true ] && [ "${REMOTE_MB:-0}" -gt 0 ] && [ "$REMOTE_MB" -lt 1800 ]; then
  warn "Server has ${REMOTE_MB} MB RAM. A Maven build needs roughly 1.5 GB and may be"
  warn "killed partway through. If it dies, add swap or build elsewhere."
fi

info "Server reachable, Docker present."

# ---------------------------------------------------------------------------
# 3. Sync the source
# ---------------------------------------------------------------------------
bold "3/6  Syncing source to ${DEPLOY_DIR}"

ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p '$DEPLOY_DIR'"

# .env is excluded here and copied separately below: --delete would otherwise
# remove the server's own copy before the new one arrives.
rsync -az --delete \
  --exclude '.git/' \
  --exclude '.idea/' \
  --exclude 'target/' \
  --exclude 'data/' \
  --exclude '.env' \
  --exclude '.DS_Store' \
  --exclude '*.log' \
  -e "ssh ${SSH_OPTS[*]}" \
  ./ "${SSH_TARGET}:${DEPLOY_DIR}/"

info "Source synced."

# ---------------------------------------------------------------------------
# 4. Secrets and port settings
# ---------------------------------------------------------------------------
bold "4/6  Writing the server .env"

# Sent over the ssh pipe rather than written to a temp file anywhere.
{
  grep -vE '^(BIND_ADDR|HOST_PORT)=' .env
  echo "BIND_ADDR=${BIND_ADDR}"
  echo "HOST_PORT=${HOST_PORT}"
} | ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cat > '${DEPLOY_DIR}/.env' && chmod 600 '${DEPLOY_DIR}/.env'"

info "Secrets written with mode 600."

# ---------------------------------------------------------------------------
# 5. Build and start
# ---------------------------------------------------------------------------
if [ "$BUILD" = true ]; then
  bold "5/6  Building and starting (first build pulls Maven, several minutes)"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose up -d --build"
else
  bold "5/6  Restarting without rebuilding"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose up -d"
fi

# ---------------------------------------------------------------------------
# 6. Wait for it to answer
# ---------------------------------------------------------------------------
bold "6/6  Waiting for the app to respond"

healthy=false
for _ in $(seq 1 40); do
  if ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
      "curl -fsS -o /dev/null -m 5 http://127.0.0.1:${HOST_PORT}/ 2>/dev/null"; then
    healthy=true
    break
  fi
  sleep 5
  printf '.'
done
printf '\n'

if [ "$healthy" = true ]; then
  info "Responding on the server."
else
  warn "No response after ~200s. Recent logs:"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose logs --tail=40"
  die "Deploy finished but the app is not answering."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo
bold "Deployed"
info "Students: http://${DEPLOY_HOST}:${HOST_PORT}/"
info "Lecturer: http://${DEPLOY_HOST}:${HOST_PORT}/admin"
echo

if [ "$BIND_ADDR" = "0.0.0.0" ]; then
  printf '\033[33m'
  cat <<'WARNING'
  ------------------------------------------------------------------
  This is plain HTTP on a public port.

  Your admin password crosses the network in the clear every time you
  log in, and so does every student name and uploaded file. Anyone on
  the path between a student and the server can read all of it.

  Fine for testing today. Before you give the URL to a class:

    1. Point a domain at this server.
    2. Put nginx in front and get a certificate:
         apt install nginx certbot python3-certbot-nginx
         certbot --nginx -d labs.your-domain.al
    3. Set BIND_ADDR=127.0.0.1 in .env on the server and redeploy, so
       only nginx can reach the app.

  The nginx server block is in the README.
  ------------------------------------------------------------------
WARNING
  printf '\033[0m'

  if ! ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
        "command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q '${HOST_PORT}'"; then
    info "If the port is unreachable from outside, open the firewall on the server:"
    info "    ufw allow ${HOST_PORT}/tcp"
  fi
fi

if [ "$FOLLOW_LOGS" = true ]; then
  echo
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "cd '$DEPLOY_DIR' && docker compose logs -f --tail=50"
fi
