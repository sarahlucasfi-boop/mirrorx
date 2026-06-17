#!/bin/bash
# MirrorX - Push para GitHub
# Uso: ./push_to_github.sh <SEU_GITHUB_TOKEN>
#
# Criar token em: https://github.com/settings/tokens
# Permissoes necessarias: repo, workflow

set -e

if [ -z "$1" ]; then
    echo "Uso: \$0 <GITHUB_TOKEN>"
    echo ""
    echo "Criar token em: https://github.com/settings/tokens"
    echo "Permissoes: repo, workflow"
    exit 1
fi

TOKEN="$1"
REPO="mirrorx"

echo "=== MirrorX - Push para GitHub ==="
echo ""

# Descobrir username
echo "[0/3] Descobrindo username..."
USERNAME=$(curl -s -H "Authorization: token $TOKEN" https://api.github.com/user | python -c "import sys,json; print(json.load(sys.stdin)['login'])" 2>/dev/null)
if [ -z "$USERNAME" ]; then
    echo "Erro: token invalido"
    exit 1
fi
echo "  Username: $USERNAME"

# Criar repositorio
echo "[1/3] Criando repositorio $USERNAME/$REPO..."
HTTP_CODE=$(curl -s -o /tmp/gh_response.json -w "%{http_code}" \
    -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Accept: application/vnd.github.v3+json" \
    https://api.github.com/user/repos \
    -d "{
        \"name\": \"$REPO\",
        \"description\": \"MirrorX - Espelhe a tela do PC no tablet via WiFi. Peer-to-peer, open source.\",
        \"private\": false,
        \"auto_init\": false,
        \"has_issues\": true,
        \"has_wiki\": false
    }")

if [ "$HTTP_CODE" = "201" ]; then
    echo "  Repositorio criado!"
elif [ "$HTTP_CODE" = "422" ]; then
    echo "  Repositorio ja existe, continuando..."
else
    echo "  Erro HTTP $HTTP_CODE"
    cat /tmp/gh_response.json
    exit 1
fi

# Configurar remote e push
echo "[2/3] Configurando remote..."
cd "$(dirname "$0")"
git remote remove origin 2>/dev/null || true
git remote add origin "https://${USERNAME}:${TOKEN}@github.com/${USERNAME}/${REPO}.git"

echo "[3/3] Fazendo push..."
git push -u origin master --tags

echo ""
echo "=== Concluido! ==="
echo "Repositorio: https://github.com/$USERNAME/$REPO"
echo ""
echo "Proximos passos:"
echo "  1. Ativar GitHub Pages (Settings > Pages > Source: master)"
echo "  2. Criar Release com binarios:"
echo "     gh release create v1.0.0 ./dist/MirrorX.exe"
