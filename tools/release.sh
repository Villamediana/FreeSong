#!/usr/bin/env bash
# Publica uma versão do FreeSong no GitHub Releases. Os celulares com o FreeSong 1.13.0 ou mais
# novo acham a versão sozinhos (ao abrir o app e numa checagem diária) e se atualizam com um toque.
#
#   tools/release.sh -n $'- Novidade um\n- Novidade dois'   # notas na linha de comando
#   tools/release.sh -F notas.md                             # notas num arquivo
#
# As notas são o que aparece na tela "Nova versão" do app (Markdown leve: "- " vira item).
# Antes: subir versionCode e versionName em app/build.gradle.kts, commitar e fazer o merge na main.
#
# O que faz: confere git e versão → compila a release assinada → confere pacote, versão e a chave
# (a mesma de sempre, senão o Android recusa a atualização) → cria a release com a APK → confere
# o que os celulares vão ler na API do GitHub (tag, tamanho e SHA-256).
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=Villamediana/FreeSong
PACKAGE=com.villamediana.sound
# SHA-256 do certificado da chave de release (~/keystores/sound-release.jks).
CERT_SHA256=463fc12f58856f182397337e0fe868497c2bffb4fa145383bea7f8dacbb14644

die() { echo "release: $*" >&2; exit 1; }

# JDK 21 da toolchain; SDK do local.properties (o mesmo que o Gradle usa), senão ANDROID_HOME.
[[ -x ${JAVA_HOME:-}/bin/java ]] || export JAVA_HOME=$HOME/.toolchain/jdk-21.0.13+9
sdk=$(sed -nE 's/^sdk\.dir=(.*)$/\1/p' local.properties 2>/dev/null || true)
for candidate in "$sdk" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
  [[ -n $candidate && -d $candidate/build-tools ]] && { export ANDROID_HOME=$candidate; break; }
done
[[ -d ${ANDROID_HOME:-}/build-tools ]] || die "Android SDK não encontrado (local.properties sdk.dir ou ANDROID_HOME)"
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"
GH=${GH:-$(command -v gh || echo "$HOME/.local/bin/gh")}
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

notes="" notes_file=""
while getopts "n:F:h" opt; do
  case $opt in
    n) notes=$OPTARG ;;
    F) notes_file=$OPTARG ;;
    *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
  esac
done
[[ -n $notes || -n $notes_file ]] || die "faltam as notas (-n texto ou -F arquivo): é o que aparece na tela de atualização"
[[ -z $notes_file || -s $notes_file ]] || die "arquivo de notas vazio ou inexistente: $notes_file"

name=$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
code=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' app/build.gradle.kts)
[[ $name =~ ^[0-9]+(\.[0-9]+)+$ && -n $code ]] || die "versionName/versionCode não lidos de app/build.gradle.kts"
tag="v$name"
apk="dist/FreeSong-$name.apk"
echo "release: FreeSong $name (build $code)"

# 1. Git: nada solto e o commit já está na main do GitHub (a tag aponta pra ele).
git diff --quiet && git diff --cached --quiet || die "há mudanças sem commit"
git fetch -q origin main
git merge-base --is-ancestor HEAD origin/main || die "HEAD não está na origin/main: faça o merge do PR antes"

# 2. Versão nova de verdade (o app compara número a número com a que está instalada).
"$GH" release view "$tag" -R "$REPO" >/dev/null 2>&1 && die "$tag já existe no GitHub"
latest=$("$GH" api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || echo v0)
python3 - "$name" "${latest#v}" <<'PY' || die "$name não é maior que a última publicada ($latest)"
import sys
a, b = ([int(x) for x in v.split(".")] for v in sys.argv[1:3])
n = max(len(a), len(b))
sys.exit(0 if a + [0] * (n - len(a)) > b + [0] * (n - len(b)) else 1)
PY

# 3. Release assinada (o Gradle falha sem a chave em ~/keystores).
./gradlew :app:assembleRelease --console=plain -q
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "$apk"

# 4. A APK é este app, nesta versão, com a chave de sempre.
# (awk lê tudo: um "head" fecharia o pipe e o pipefail mataria o script com SIGPIPE)
badging=$("$BUILD_TOOLS/aapt2" dump badging "$apk" | awk 'NR == 1')
[[ $badging == *"name='$PACKAGE'"* && $badging == *"versionCode='$code'"* && $badging == *"versionName='$name'"* ]] \
  || die "APK não bate com $PACKAGE $name ($code): $badging"
cert=$("$BUILD_TOOLS/apksigner" verify --print-certs "$apk" | awk '/Signer.*certificate SHA-256 digest:/ && !seen { print $NF; seen = 1 }')
[[ $cert == "$CERT_SHA256" ]] || die "APK assinada com outra chave ($cert): os celulares recusariam a atualização"
sha=$(sha256sum "$apk" | cut -d' ' -f1)
size=$(stat -c %s "$apk")
echo "release: $apk $size bytes sha256=$sha"

# 5. Publicar.
if [[ -n $notes_file ]]; then
  "$GH" release create "$tag" "$apk" -R "$REPO" --target "$(git rev-parse HEAD)" --title "FreeSong $name" --notes-file "$notes_file"
else
  "$GH" release create "$tag" "$apk" -R "$REPO" --target "$(git rev-parse HEAD)" --title "FreeSong $name" --notes "$notes"
fi

# 6. O que os celulares vão ler: /releases/latest com esta tag, esta APK, este SHA-256.
for _ in 1 2 3 4 5 6; do
  seen=$("$GH" api "repos/$REPO/releases/latest" --jq '[.tag_name, (.assets[] | select(.name|endswith(".apk")) | .size, .digest)] | join(" ")' 2>/dev/null || true)
  [[ $seen == "$tag $size sha256:$sha" ]] && { echo "release: published $tag"; exit 0; }
  sleep 5
done
die "a release subiu, mas /releases/latest ainda diz: '$seen' (esperado: '$tag $size sha256:$sha')"
