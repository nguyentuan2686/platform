# shellcheck shell=bash
# Steps argocd (pinned install.yaml + Application health customization) and gitops-root (the app-of-apps root).

ARGOCD_NS=argocd
DEFAULT_GITOPS_REPO_URL=https://github.com/nguyentuan2686/platform.git

argocd_ns_manifest() {
  printf 'apiVersion: v1\nkind: Namespace\nmetadata:\n  name: %s\n' "$ARGOCD_NS"
}

argocd_workloads_ready() {
  local out
  out=$(kc -n "$ARGOCD_NS" get deployments,statefulsets \
    -o jsonpath='{range .items[*]}{.spec.replicas} {.status.readyReplicas}{"\n"}{end}') || return 1
  [ -n "$out" ] && ! awk '$1 != $2 {bad=1} END {exit !bad}' <<<"$out"
}

# render_argocd_cm: argocd-cm-patch.yaml, with the CI-only health branch (between `-- ci-mode-begin` and
# `-- ci-mode-end`) kept only when GITOPS_CI_MODE=1. Prints the manifest on stdout.
render_argocd_cm() {
  local src="$BOOTSTRAP_DIR/argocd-cm-patch.yaml"
  [ "$(grep -c -- '-- ci-mode-begin$' "$src")" = 1 ] && [ "$(grep -c -- '-- ci-mode-end$' "$src")" = 1 ] \
    || die "$src: expected one ci-mode-begin and one ci-mode-end marker"
  if [ "$GITOPS_CI_MODE" = 1 ]; then
    grep -v -- '-- ci-mode-\(begin\|end\)$' "$src"
  else
    sed '/-- ci-mode-begin$/,/-- ci-mode-end$/d' "$src"
  fi
}

# prepull_images <manifest>: pull (through the CRI) every image the manifest names before the pods need them, retrying
# with backoff. Anonymous registries (public.ecr.aws for argocd-redis) answer 429 to busy shared IPs such as CI
# runners, and kubelet's own ImagePullBackOff alone outlasted the 600 s readiness deadline (lesson 2026-11-28); ECR kept
# answering 429 for over 5 minutes, so a Docker official image (public.ecr.aws/docker/library/<name>) falls back to the
# same official image on Google's Docker Hub mirror, tagged back to the manifest's name so the pod finds it locally.
pull_image() {
  local img="$1" err mirror
  err="$("$K3S_BIN" crictl pull "$img" 2>&1 >/dev/null)" && return 0
  note "pull $img: ${err##*$'\n'}"
  case "$img" in
    public.ecr.aws/docker/library/*) mirror="mirror.gcr.io/library/${img#public.ecr.aws/docker/library/}" ;;
    *) return 1 ;;
  esac
  err="$("$K3S_BIN" crictl pull "$mirror" 2>&1 >/dev/null)" || { note "pull $mirror: ${err##*$'\n'}"; return 1; }
  "$K3S_BIN" ctr -n k8s.io images tag --force "$mirror" "$img" >/dev/null || return 1
  "$K3S_BIN" crictl inspecti "$img" >/dev/null 2>&1 || return 1
  note "$img taken from $mirror"
}

prepull_images() {
  local manifest="$1" images img out delay
  out="$(grep -E '^[[:space:]]+image:[[:space:]]' "$manifest")" || return 0
  images="$(awk '{print $2}' <<<"$out" | tr -d '"'"'" | sort -u)"
  for img in $images; do
    "$K3S_BIN" crictl inspecti "$img" >/dev/null 2>&1 && continue
    delay=10
    until pull_image "$img"; do
      [ "$delay" -le 160 ] || die "cannot pull $img (gave up after backoff up to 160s)"
      note "pull $img failed, retrying in ${delay}s"
      sleep "$delay"
      delay=$((delay * 2))
    done
    note "pulled $img"
  done
}

step_argocd() {
  local file="$CACHE_DIR/argocd-$ARGOCD_VERSION/install.yaml" what=() ns_file cm_file install_file
  fetch_verified "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGOCD_COMMIT/manifests/install.yaml" \
    "$(pin_sha ARGOCD)" "$file"
  prepull_images "$file"
  # Every image in the pinned install.yaml has a fixed tag, so IfNotPresent keeps the pre-pulled copy: with Always a pod
  # restart asks the registry again and fails on a 429 even with the image on the node.
  install_file="$(mktemp)"
  sed "s/imagePullPolicy: Always$/imagePullPolicy: IfNotPresent/" "$file" >"$install_file"

  ns_file="$(mktemp)"
  argocd_ns_manifest >"$ns_file"
  if kube_sync "$ns_file" bootstrap-argocd; then what+=("namespace"); fi
  rm -f "$ns_file"
  if kube_sync "$install_file" bootstrap-argocd "$ARGOCD_NS"; then what+=("Argo CD $ARGOCD_VERSION"); fi
  rm -f "$install_file"
  cm_file="$(mktemp)"
  render_argocd_cm >"$cm_file"
  if kube_sync "$cm_file" bootstrap-argocd-cm; then what+=("Application health customization"); fi
  rm -f "$cm_file"

  kc wait --for=condition=Established --timeout=120s \
    crd/applications.argoproj.io crd/appprojects.argoproj.io >&2 || die "Argo CD CRDs not established"
  wait_until 600 "every Argo CD Deployment/StatefulSet to be ready" argocd_workloads_ready

  if [ "${#what[@]}" -gt 0 ]; then status_changed argocd "$(IFS=,; echo "${what[*]}")"; else status_ok argocd; fi
}

# render_root: deploy/argocd/root.yaml with repoURL/targetRevision from GITOPS_REPO_URL/GITOPS_REVISION. When the
# revision is not main, the repo is not the default, or GITOPS_CI_MODE=1, spec.source.kustomize.patches make every child
# Application follow the same repo/revision. GITOPS_CI_MODE=1 also strips syncPolicy.automated from prod-tunnel and
# prod-apps (no prod images pulled, no public tunnel in CI) and marks them platform.dev/ci-manual-sync (see
# argocd-cm-patch.yaml). Prints the manifest on stdout.
render_root() {
  local src="$REPO_ROOT/deploy/argocd/root.yaml" line app
  [[ "$GITOPS_REVISION" =~ ^[A-Za-z0-9._/-]+$ ]] || die "GITOPS_REVISION has unexpected characters: $GITOPS_REVISION"
  [[ "$GITOPS_REPO_URL" =~ ^https://[A-Za-z0-9._/-]+$ ]] || die "GITOPS_REPO_URL must be an https URL: $GITOPS_REPO_URL"
  for line in '    repoURL: ' '    targetRevision: ' '    path: deploy/argocd/apps'; do
    [ "$(grep -c "^$line" "$src")" = 1 ] || die "$src: expected exactly one line starting [$line]"
  done
  {
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in
        '    repoURL: '*) echo "    repoURL: $GITOPS_REPO_URL" ;;
        '    targetRevision: '*) echo "    targetRevision: $GITOPS_REVISION" ;;
        '    path: deploy/argocd/apps')
          echo "$line"
          if [ "$GITOPS_REVISION" != main ] || [ "$GITOPS_CI_MODE" = 1 ] || [ "$GITOPS_REPO_URL" != "$DEFAULT_GITOPS_REPO_URL" ]; then
            echo "    kustomize:"
            echo "      patches:"
            echo "        - target: {group: argoproj.io, kind: Application}"
            echo "          patch: |-"
            echo "            - {op: replace, path: /spec/source/targetRevision, value: \"$GITOPS_REVISION\"}"
            echo "            - {op: replace, path: /spec/source/repoURL, value: \"$GITOPS_REPO_URL\"}"
            echo "        - target: {group: argoproj.io, kind: AppProject, name: prod}"
            echo "          patch: |-"
            echo "            - {op: replace, path: /spec/sourceRepos, value: [\"$GITOPS_REPO_URL\"]}"
            if [ "$GITOPS_CI_MODE" = 1 ]; then
              for app in prod-tunnel prod-apps; do
                echo "        - target: {group: argoproj.io, kind: Application, name: $app}"
                echo "          patch: |-"
                echo "            - {op: remove, path: /spec/syncPolicy/automated}"
                echo "            - {op: add, path: /metadata/annotations/platform.dev~1ci-manual-sync, value: \"true\"}"
              done
              # CI seals its own test secrets with the runner's throwaway key; the prod SealedSecrets in Git are sealed
              # for the prod key and never decrypt there. prod-sealed keeps whatever SealedSecret spec is live, so a
              # selfHeal does not put the undecryptable Git copies back over the CI ones.
              echo "        - target: {group: argoproj.io, kind: Application, name: prod-sealed}"
              echo "          patch: |-"
              echo "            - {op: add, path: /spec/ignoreDifferences, value: [{group: bitnami.com, kind: SealedSecret, jsonPointers: [/spec]}]}"
              echo "            - {op: replace, path: /spec/syncPolicy/syncOptions, value: [ServerSideApply=true, RespectIgnoreDifferences=true]}"
            fi
          fi
          ;;
        *) echo "$line" ;;
      esac
    done
  } <"$src"
}

step_gitops_root() {
  local file
  file="$(mktemp)"
  render_root >"$file"
  if kube_sync "$file" bootstrap-gitops-root; then
    status_changed gitops-root "root -> $GITOPS_REVISION (ci mode $GITOPS_CI_MODE)"
  else
    status_ok gitops-root
  fi
  rm -f "$file"
}
