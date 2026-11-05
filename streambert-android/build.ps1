# Streambert Mobile - APK build script (no Gradle / no Android Studio required)
# Uses a portable JDK + Android SDK build-tools from the local toolchain folder.
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$fallbackTools = Join-Path $env:LOCALAPPDATA "Temp\opencode\android-tools"

# --- Locate a JDK (JAVA_HOME first, then the portable fallback) -----------
$jdkCandidates = @($env:JAVA_HOME, (Join-Path $fallbackTools "jdk\jdk-21.0.13+9"))
$jdk = $null
foreach ($c in $jdkCandidates) {
  if ($c -and (Test-Path (Join-Path $c "bin\javac.exe"))) { $jdk = (Resolve-Path $c).Path; break }
}
if (!$jdk) { throw "No JDK found. Set JAVA_HOME or install the portable JDK under $fallbackTools." }

# --- Locate an Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT first) --------
$sdkCandidates = @(
  $env:ANDROID_HOME,
  $env:ANDROID_SDK_ROOT,
  (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
  (Join-Path $fallbackTools "android-sdk")
)
$sdk = $null
foreach ($c in $sdkCandidates) {
  if ($c -and (Test-Path (Join-Path $c "platforms"))) { $sdk = (Resolve-Path $c).Path; break }
}
if (!$sdk) { throw "No Android SDK found. Set ANDROID_HOME or install the SDK under $fallbackTools." }

# --- Pick the newest installed platform + build-tools ---------------------
$platformDir = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
  Where-Object { Test-Path (Join-Path $_.FullName "android.jar") } |
  Sort-Object { [int]($_.Name -replace '\D', '') } -Descending | Select-Object -First 1
if (!$platformDir) { throw "No Android platform (android.jar) installed in $sdk\platforms" }
$androidJar = Join-Path $platformDir.FullName "android.jar"

$btDir = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
  Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$btDir) { throw "No build-tools installed in $sdk\build-tools" }
$bt = $btDir.FullName

Write-Host "JDK:     $jdk"
Write-Host "SDK:     $sdk"
Write-Host "Platform: $($platformDir.Name)"
Write-Host "Build:   $($btDir.Name)"

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$bt;$env:Path"

$build = Join-Path $root "build"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $build, "$build\gen", "$build\classes", "$build\dex" -Force | Out-Null

# Version info comes from the manifest so the two can never drift apart.
$manifestText = Get-Content "$root\AndroidManifest.xml" -Raw
if ($manifestText -notmatch 'android:versionCode="(\d+)"') { throw "versionCode missing from AndroidManifest.xml" }
$versionCode = $Matches[1]
if ($manifestText -notmatch 'android:versionName="([^"]+)"') { throw "versionName missing from AndroidManifest.xml" }
$versionName = $Matches[1]
Write-Host "Version: $versionName ($versionCode)"

Write-Host "[1/7] Compiling resources..."
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] Linking resources..."
& "$bt\aapt2.exe" link `
  -o "$build\base.apk" `
  -I "$androidJar" `
  --manifest "$root\AndroidManifest.xml" `
  --java "$build\gen" `
  --min-sdk-version 23 `
  --target-sdk-version 34 `
  --version-code $versionCode `
  --version-name $versionName `
  "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] Compiling Java..."
$javaFiles = @(Get-ChildItem "$root\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem "$build\gen" -Recurse -Filter R.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -source 8 -target 8 -Xlint:-options -classpath "$androidJar" -d "$build\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] Dexing..."
$classFiles = @(Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --lib "$androidJar" --min-api 23 --output "$build\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] Packaging assets + classes.dex..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = "$build\unsigned.apk"
Copy-Item "$build\base.apk" $apkPath -Force
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, "Update")
try {
  $assetRoot = (Resolve-Path "$root\assets").Path.TrimEnd('\')
  $files = @(Get-ChildItem "$root\assets" -Recurse -File)
  foreach ($f in $files) {
    $rel = $f.FullName.Substring($assetRoot.Length + 1).Replace('\', '/')
    $entryName = "assets/$rel"
    $old = $zip.GetEntry($entryName)
    if ($old) { $old.Delete() }
    $entry = $zip.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
    $out = $entry.Open()
    $in = [System.IO.File]::OpenRead($f.FullName)
    try { $in.CopyTo($out) } finally { $in.Close(); $out.Close() }
  }
  $dexPath = "$build\dex\classes.dex"
  $old = $zip.GetEntry("classes.dex")
  if ($old) { $old.Delete() }
  $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
  $out = $entry.Open()
  $in = [System.IO.File]::OpenRead($dexPath)
  try { $in.CopyTo($out) } finally { $in.Close(); $out.Close() }
} finally {
  $zip.Dispose()
}

& "$bt\zipalign.exe" -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/7] Signing..."
$ks = Join-Path $root "streambert-debug.keystore"
if (!(Test-Path $ks)) {
  & "$jdk\bin\keytool.exe" -genkeypair -v `
    -keystore "$ks" -storepass android -alias androiddebugkey -keypass android `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=Streambert Mobile Debug,O=Streambert,C=US"
  if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android `
  --out "$root\Streambert-Mobile.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "[7/7] Verifying..."
& "$bt\apksigner.bat" verify --print-certs "$root\Streambert-Mobile.apk"
if ($LASTEXITCODE -ne 0) { throw "signature verification failed" }

$size = [math]::Round((Get-Item "$root\Streambert-Mobile.apk").Length / 1MB, 2)
Write-Host ""
Write-Host "Done: $root\Streambert-Mobile.apk ($size MB)"
