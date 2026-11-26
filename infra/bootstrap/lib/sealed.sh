# shellcheck shell=bash
# Step sealed-secrets: optionally restore an existing sealing key (host migration, spec §12), then the pinned
# controller.yaml. A restored key whose certificate is older than the controller's key-renew period (30 days) is
# rotated at controller start: the old key keeps decrypting, but re-fetch the cert (kubeseal --fetch-cert) afterwards.

sealed_key_manifest() { # $1 = key dir with tls.crt + tls.key
  kc create secret tls sealed-secrets-key-restored -n kube-system \
    --cert="$1/tls.crt" --key="$1/tls.key" --dry-run=client -o yaml \
    | sed 's/^metadata:$/metadata:\n  labels:\n    sealedsecrets.bitnami.com\/sealed-secrets-key: active/' \
    | grep -v '^  creationTimestamp: null$'
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
