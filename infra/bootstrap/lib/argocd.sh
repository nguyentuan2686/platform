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

step_argocd() {
  local file="$CACHE_DIR/argocd-$ARGOCD_VERSION/install.yaml" what=() ns_file
  fetch_verified "https://raw.githubusercontent.com/argoproj/argo-cd/$ARGOCD_COMMIT/manifests/install.yaml" \
    "$(pin_sha ARGOCD)" "$file"

  ns_file="$(mktemp)"
  argocd_ns_manifest >"$ns_file"
  if kube_sync "$ns_file" bootstrap-argocd; then what+=("namespace"); fi
  rm -f "$ns_file"
  if kube_sync "$file" bootstrap-argocd "$ARGOCD_NS"; then what+=("Argo CD $ARGOCD_VERSION"); fi
  if kube_sync "$BOOTSTRAP_DIR/argocd-cm-patch.yaml" bootstrap-argocd-cm; then what+=("Application health customization"); fi

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
