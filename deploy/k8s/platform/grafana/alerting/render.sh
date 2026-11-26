#!/bin/sh
# Init container `alerting-render` of the lgtm pod (same image, so no extra pull): `sh /alerting/render.sh`.
# Copies rules.yaml into the emptyDir that the lgtm container mounts as Grafana's provisioning/alerting dir. When
# TELEGRAM_BOT_TOKEN is non-empty it also renders the Telegram contact point and the notification policy from the
# templates; otherwise it renders neither and Grafana keeps its built-in default policy. The token is only ever
# written into the rendered file in that emptyDir; it never reaches the Grafana container env.
set -eu
SRC="${ALERTING_SRC:-/alerting}"
OUT="${ALERTING_OUT:-/provisioning-alerting}"

# render TEMPLATE -> stdout with @NAME@ replaced by the value of env NAME (literal replacement: awk ENVIRON, no escaping)
render() {
  awk '
    function lit(s, from, to,    out, i) {
      out = ""
      while ((i = index(s, from)) > 0) { out = out substr(s, 1, i - 1) to; s = substr(s, i + length(from)) }
      return out s
    }
    { $0 = lit($0, "@TELEGRAM_BOT_TOKEN@", ENVIRON["TELEGRAM_BOT_TOKEN"]); $0 = lit($0, "@TELEGRAM_CHAT_ID@", ENVIRON["TELEGRAM_CHAT_ID"]); print }
  ' "$1"
}

cp "$SRC/rules.yaml" "$OUT/rules.yaml"
if [ -n "${TELEGRAM_BOT_TOKEN:-}" ]; then
  if [ -z "${TELEGRAM_CHAT_ID:-}" ]; then echo "alerting: TELEGRAM_BOT_TOKEN set but TELEGRAM_CHAT_ID empty" >&2; exit 1; fi
  render "$SRC/contact-point.tmpl.yaml" >"$OUT/contact-point.yaml"
  render "$SRC/policy.tmpl.yaml" >"$OUT/policy.yaml"
  echo "alerting: rules + telegram contact point + policy rendered"
else
  echo "alerting: TELEGRAM_BOT_TOKEN unset, no contact point"
fi
