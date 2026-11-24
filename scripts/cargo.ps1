# Thin PowerShell equivalent of scripts/cargo.sh. Usage: scripts\cargo.ps1 <cargo args>
$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $Root
$Toolchain = (Select-String -Path rust-toolchain.toml -Pattern '^channel\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$Image = "platform-rust-dev:$Toolchain"

# Native commands from here on: rely on $LASTEXITCODE, not 'Stop'. On Windows PowerShell 5.1 a
# redirected native stderr line (e.g. "No such image" from the probe) becomes an error record,
# which 'Stop' would turn into a terminating error before the image is ever built.
$ErrorActionPreference = 'Continue'

docker image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) {
  Write-Host "Building $Image (first use, takes several minutes)..."
  docker build -t $Image tools/rust-dev
  if ($LASTEXITCODE -ne 0) {
    Write-Host "docker build of $Image failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
  }
}

docker run --rm -i `
  -v "${Root}:/work" `
  -v platform-cargo-registry:/usr/local/cargo/registry `
  -v platform-cargo-git:/usr/local/cargo/git `
  -v //var/run/docker.sock:/var/run/docker.sock `
  -e CARGO_TARGET_DIR=/work/target/docker `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  --add-host=host.docker.internal:host-gateway `
  -v platform-cargo-advisory:/usr/local/cargo/advisory-dbs `
  -e RUST_LOG -e RUST_BACKTRACE `
  -w /work $Image cargo @args
exit $LASTEXITCODE
