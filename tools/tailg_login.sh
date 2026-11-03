#!/usr/bin/env bash
# 台铃账号登录探针（仅用于本人账号/车辆）
#
# 用法:
#   bash tools/tailg_login.sh <手机号>
#       从官方 App 正常走登录、收到短信后，在这里输入验证码完成 API 登录。
#
#   bash tools/tailg_login.sh <手机号> --send <captchaPassToken>
#       已通过官方滑块页拿到 captchaPassToken 时，先调用 3.6.2 的
#       POST app/sms/loginCode 触发短信，再输入验证码。
#
# 说明:
#   - 3.6.2 起登录页不再使用 app/getCode，而是:
#       腾讯滑块 -> POST app/captcha/slider/verify {ticket,randstr}
#       -> captchaPassToken -> POST app/sms/loginCode?phone=&captchaPassToken=
#   - 本脚本不实现滑块、不绕过任何验证。最稳妥的方式是用官方 App 正常拿到短信码，
#     再让本脚本只做 app/login。
#   - 短信接口必须带 okhttp/* UA；脚本已内置，否则网关会返回假成功但不发短信。
#   - 不保存手机号；验证码只从标准输入读取一次。
#   - Token 与 carStatus 原始响应写入 local/ 并设为 600，请不要提交/分享。
set -euo pipefail

PHONE="${1:?用法: bash tools/tailg_login.sh <手机号> [--send <captchaPassToken>]}"
shift || true

CAPTCHA_TOKEN=""
if [ "${1:-}" = "--send" ] || [ "${1:-}" = "--captcha-token" ]; then
  CAPTCHA_TOKEN="${2:?--send 后需要 captchaPassToken}"
fi

BASE="https://www.tailgdd.com/v1/api"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/local"
mkdir -p "$LOCAL"

# 必须使用 OkHttp UA：网关对非 okhttp/* UA 的短信接口会返回假成功，不会真正发码。
HDRS=(-H 'Forward-Service-Ip: localhost' -H 'language: zh_CN' -H 'User-Agent: okhttp/4.12.0')

if [ -n "$CAPTCHA_TOKEN" ]; then
  echo "[1/3] 调用 app/sms/loginCode 触发短信: $PHONE"
  curl -sS -X POST -G "$BASE/app/sms/loginCode" \
    --data-urlencode "phone=$PHONE" \
    --data-urlencode "captchaPassToken=$CAPTCHA_TOKEN" \
    "${HDRS[@]}" -H 'Authorization: ' || true
  echo
else
  echo "[1/3] 跳过发码：3.6.2 需要腾讯滑块 captchaPassToken。"
  echo "      请在官方 App 正常走登录并收到短信，然后在这里输入验证码。"
fi

read -r -p "请输入收到的短信验证码: " CODE
if [ -z "$CODE" ]; then
  echo "验证码为空，退出" >&2
  exit 1
fi

AID_FILE="$LOCAL/android_id"
if [ ! -s "$AID_FILE" ]; then
  od -An -N8 -tx1 /dev/urandom | tr -d ' \n' > "$AID_FILE"
  chmod 600 "$AID_FILE"
fi
AID="$(cat "$AID_FILE")"

MAC_CODE="$(python3 -I - "$AID" <<'PY'
import hashlib, sys, uuid
d = bytearray(hashlib.md5(sys.argv[1].encode()).digest())
d[6] = (d[6] & 0x0F) | 0x30          # version 3
d[8] = (d[8] & 0x3F) | 0x80          # IETF variant
print("2" + uuid.UUID(bytes=bytes(d)).hex)
PY
)"

echo "[2/3] 登录..."
LOGIN_BODY="$(python3 -I - "$MAC_CODE" "$PHONE" "$CODE" <<'PY'
import json, sys
print(json.dumps({
    "macCode": sys.argv[1],
    "phone": sys.argv[2],
    "smsCode": sys.argv[3],
    "autoCompleteUserDetail": "true",
}, ensure_ascii=False))
PY
)"
HEADER_FILE="$(mktemp)"
BODY_FILE="$(mktemp)"
trap 'rm -f "$HEADER_FILE" "$BODY_FILE"' EXIT

curl -sS -D "$HEADER_FILE" -o "$BODY_FILE" -X POST "$BASE/app/login" \
  "${HDRS[@]}" -H 'Authorization: ' -H 'Content-Type: application/json' \
  --data "$LOGIN_BODY"

TOKEN="$(awk 'BEGIN{IGNORECASE=1} /^Authorization:/{sub(/^[^:]*:[[:space:]]*/,""); sub(/\r$/,""); v=$0} END{print v}' "$HEADER_FILE")"

if [ -z "$TOKEN" ]; then
  echo "登录未返回 Authorization 头，响应体:" >&2
  cat "$BODY_FILE" >&2
  echo >&2
  exit 1
fi

printf '%s' "$TOKEN" > "$LOCAL/token"
chmod 600 "$LOCAL/token"
echo "登录成功，Token 已保存到 local/token（600）"

echo "[3/3] 拉取 carStatus..."
# 官方实现: map.put("phoneMode", DeviceUtils.getModel())，即 Build.MODEL。
# 可用 PHONE_MODEL 环境变量覆盖；默认 "Android" 仅作为通用值。
PHONE_MODE="${PHONE_MODEL:-Android}"
STATUS_BODY="$(python3 -I - "$PHONE_MODE" <<'PY'
import json, sys
print(json.dumps({"phoneMode": sys.argv[1]}, ensure_ascii=False))
PY
)"
curl -sS -X POST "$BASE/app/centralControl/carStatus" \
  "${HDRS[@]}" -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
  --data "$STATUS_BODY" -o "$LOCAL/carStatus.json"
chmod 600 "$LOCAL/carStatus.json"

python3 -I - "$LOCAL/carStatus.json" <<'PY'
import json, sys

with open(sys.argv[1], encoding="utf-8") as f:
    raw = json.load(f)

data = raw.get("data") if isinstance(raw, dict) and "data" in raw else raw
if not isinstance(data, dict):
    print(json.dumps(raw, ensure_ascii=False, indent=2)[:4000])
    raise SystemExit

show = ["carId", "carName", "carNickName", "carType", "modelType",
        "isTelligence", "isBluetooth", "btname", "btmac", "mac",
        "shareCarFlag", "online", "defenceStatus", "electricQuantity",
        "frame", "imei", "imeiGps"]
for k in show:
    if k in data and data[k] is not None:
        v = data[k]
        if k in ("imei", "imeiGps") and isinstance(v, str) and len(v) >= 8:
            v = v[:4] + "****" + v[-4:]
        print(f"{k}: {v}")

pi = data.get("passwordInfo")
if isinstance(pi, dict):
    main = pi.get("main")
    children = pi.get("children") or []
    print("passwordInfo.main: " + ("已下发 (%d 位)" % len(str(main)) if main else "空"))
    print("passwordInfo.children: %d 个" % len(children))
print("\n完整 carStatus 已保存到 local/carStatus.json（600，勿分享）")
PY
