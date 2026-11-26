# shellcheck shell=bash
# Step sealed-secrets: optionally restore the sealing keys (host migration, spec §12): SEALED_SECRETS_KEYS_FILE (the JSON
# backup of ALL keys, preferred) and/or SEALED_SECRETS_KEY_DIR (one tls.crt + tls.key), then the pinned
# controller.yaml. A restored key whose certificate is older than the controller's key-renew period (30 days) is
# rotated at controller start: the old key keeps decrypting, but re-fetch the cert (kubeseal --fetch-cert) afterwards.

sealed_key_manifest() { # $1 = key dir with tls.crt + tls.key
  kc create secret tls sealed-secrets-key-restored -n kube-system \
    --cert="$1/tls.crt" --key="$1/tls.key" --dry-run=client -o yaml \
    | sed 's/^metadata:$/metadata:\n  labels:\n    sealedsecrets.bitnami.com\/sealed-secrets-key: active/' \
    | grep -v '^  creationTimestamp: null$'
}


# sealed_keys_manifest_from_file <backup.json>: the backup of ALL sealing keys (go-live step 3:
# `kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o json`) as a JSON List of clean
# kubernetes.io/tls Secrets in kube-system, original names, label `active`, nothing server-set (uid, resourceVersion,
# managedFields, ...). Renewal does not re-encrypt existing SealedSecrets, so every old key is needed for the seals made
# with it. Exit 1 with a message on stderr when the file has no usable key.
sealed_keys_manifest_from_file() {
  python3 - "$1" <<'PY'
import json, sys
try:
    doc = json.load(open(sys.argv[1]))
except (OSError, ValueError) as e:
    sys.exit(f"cannot read {sys.argv[1]} as JSON: {e}")
items = doc.get("items", [doc]) if isinstance(doc, dict) else []
out = []
for s in items:
    if not isinstance(s, dict) or s.get("kind", "Secret") != "Secret":
        sys.exit("not a Secret in the key backup")
    data, name = s.get("data") or {}, (s.get("metadata") or {}).get("name", "")
    if not name or not data.get("tls.crt") or not data.get("tls.key"):
        sys.exit(f"Secret {name or '?'} has no name, tls.crt or tls.key")
    out.append({"apiVersion": "v1", "kind": "Secret", "type": "kubernetes.io/tls",
                "metadata": {"name": name, "namespace": "kube-system",
                             "labels": {"sealedsecrets.bitnami.com/sealed-secrets-key": "active"}},
                "data": {"tls.crt": data["tls.crt"], "tls.key": data["tls.key"]}})
if not out:
    sys.exit("no sealing key in the backup")
json.dump({"apiVersion": "v1", "kind": "List", "items": out}, sys.stdout, indent=1, sort_keys=True)
print()
print(f"{len(out)} sealing key(s): " + ", ".join(i["metadata"]["name"] for i in out), file=sys.stderr)
PY
}

step_sealed() {
  local file="$CACHE_DIR/sealed-secrets-$SEALED_SECRETS_VERSION/controller.yaml" what=() key_file key_changed=0
  fetch_verified "https://github.com/bitnami-labs/sealed-secrets/releases/download/v$SEALED_SECRETS_VERSION/controller.yaml" \
    "$(pin_sha SEALED_SECRETS)" "$file"

  if [ -n "${SEALED_SECRETS_KEY_DIR:-}" ]; then
    [ -s "$SEALED_SECRETS_KEY_DIR/tls.crt" ] && [ -s "$SEALED_SECRETS_KEY_DIR/tls.key" ] \
      || die "SEALED_SECRETS_KEY_DIR=$SEALED_SECRETS_KEY_DIR must contain tls.crt and tls.key"
    key_file="$(mktemp)"
    chmod 600 "$key_file"
    sealed_key_manifest "$SEALED_SECRETS_KEY_DIR" >"$key_file" || { rm -f "$key_file"; die "cannot build the restored key Secret"; }
    grep -q 'sealedsecrets.bitnami.com/sealed-secrets-key: active' "$key_file" || { rm -f "$key_file"; die "restored key Secret has no active label"; }
    if kube_sync "$key_file" bootstrap-sealed-key; then what+=("restored sealing key"); key_changed=1; fi
    rm -f "$key_file"
  fi
  if [ -n "${SEALED_SECRETS_KEYS_FILE:-}" ]; then
    key_file="$(mktemp)"
    chmod 600 "$key_file"
    sealed_keys_manifest_from_file "$SEALED_SECRETS_KEYS_FILE" >"$key_file" \
      || { rm -f "$key_file"; die "SEALED_SECRETS_KEYS_FILE=$SEALED_SECRETS_KEYS_FILE is not a usable sealing-key backup"; }
    if kube_sync "$key_file" bootstrap-sealed-keys; then what+=("restored sealing keys from backup"); key_changed=1; fi
    rm -f "$key_file"
  fi

  if kube_sync "$file" bootstrap-sealed-secrets; then
    what+=("controller $SEALED_SECRETS_VERSION")
  elif [ "$key_changed" = 1 ]; then
    # The controller reads its keys at start: a key restored under a running controller needs a restart.
    kc -n kube-system rollout restart deployment/sealed-secrets-controller >&2
  fi
  kc -n kube-system rollout status deployment/sealed-secrets-controller --timeout=300s >&2 \
    || die "sealed-secrets-controller did not become ready"

  if [ "${#what[@]}" -gt 0 ]; then status_changed sealed-secrets "$(IFS=,; echo "${what[*]}")"; else status_ok sealed-secrets; fi
}
