# Manual APK build: aapt2 + javac + d8 + zipalign + apksigner
# Prereqs: tools\jdk17 (JDK), android-sdk (platforms;android-36, build-tools;36.0.0),
#          tools\downloads\api-82.jar (Xposed API)
# Native tools (javac/d8) write notes to stderr; Continue + explicit $LASTEXITCODE checks.
$ErrorActionPreference = "Continue"

$root  = "E:\deepseek harness1\NotificationSinking"
$tools = "E:\deepseek harness1\tools"
$sdk   = "E:\deepseek harness1\android-sdk"
$jdk   = "$tools\jdk17"
$build = "$root\build"
$apiJar = "$tools\downloads\api-82.jar"
$apiModernJar = "$tools\downloads\aar-ext\classes.jar"   # libxposed API 102 (modern entry)
$apiServiceJar = "$tools\downloads\service-ext\classes.jar"  # libxposed service 102 (app-side bridge)
$python = "C:\Users\a\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"

$bt    = "$sdk\build-tools\36.0.0"
$plat  = "$sdk\platforms\android-36"
if (-not (Test-Path "$bt\aapt2.exe")) { throw "build-tools missing: $bt" }
if (-not (Test-Path "$plat\android.jar")) { throw "platform missing: $plat" }

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
$java = "$jdk\bin\java.exe"

New-Item -ItemType Directory -Force -Path $build | Out-Null
if (Test-Path "$build\gen")   { Remove-Item "$build\gen" -Recurse -Force }
if (Test-Path "$build\classes") { Remove-Item "$build\classes" -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$build\classes" | Out-Null

"== [1/8] preprocess manifest (placeholder + package attr) =="
$mf = Get-Content "$root\app\src\main\AndroidManifest.xml" -Raw
$mf = $mf.Replace('${applicationId}', 'com.example.notificationsinking')
$mf = $mf.Replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.notificationsinking">')
[System.IO.File]::WriteAllText("$build\AndroidManifest.xml", $mf, (New-Object System.Text.UTF8Encoding($false)))

"== [2/8] aapt2 compile resources =="
& "$bt\aapt2.exe" compile --dir "$root\app\src\main\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

"== [3/8] aapt2 link (assets + version) =="
& "$bt\aapt2.exe" link -o "$build\base.apk" `
    -I "$plat\android.jar" `
    --manifest "$build\AndroidManifest.xml" `
    --custom-package com.example.notificationsinking `
    --java "$build\gen" `
    -A "$root\app\src\main\assets" `
    --min-sdk-version 27 --target-sdk-version 36 `
    --version-code 143 --version-name "1.13.0" `
    "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

"== [4/8] javac compile (android.jar + api jars + libxposed service) =="
$srcs = @(Get-ChildItem -Recurse -Filter *.java "$root\app\src\main\java" | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem -Recurse -Filter *.java "$build\gen" | ForEach-Object { $_.FullName })
$javacArgs = @('-encoding','UTF-8','-source','17','-target','17','-nowarn',
               '-classpath', "$plat\android.jar;$apiJar;$apiModernJar;$apiServiceJar",
               '-d', "$build\classes") + $srcs
& "$jdk\bin\javac.exe" @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

"== [5/8] jar + d8 -> classes.dex (merge libxposed service) =="
& "$jdk\bin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --lib "$plat\android.jar" --lib "$apiJar" --lib "$apiModernJar" --lib "$apiServiceJar" `
    --min-api 27 --output "$build" "$build\classes.jar" "$apiServiceJar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

"== [6/8] append classes.dex + META-INF/xposed payloads into APK =="
& $python "$tools\pack_extra.py" "$build\base.apk" "$build\classes.dex" "$root\app\src\main\resources"
if ($LASTEXITCODE -ne 0) { throw "pack payloads failed" }

"== [7/8] zipalign =="
& "$bt\zipalign.exe" -f 4 "$build\base.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

"== [8/8] sign (release keypair; keep the keystore for future updates) =="
$ks = "$root\notificationsinking.keystore"
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks `
        -storepass "nsink2026" -keypass "nsink2026" -alias notificationsinking `
        -dname "CN=NotificationSinking, OU=Dev, O=NotificationSinking, C=CN" `
        -keyalg RSA -keysize 2048 -validity 10950
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:nsink2026" --key-pass "pass:nsink2026" `
    --out "$root\NotificationSinking-v1.13.0.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

"== verify =="
& "$bt\apksigner.bat" verify --print-certs "$root\NotificationSinking-v1.13.0.apk"
"== badging =="
& "$bt\aapt2.exe" dump badging "$root\NotificationSinking-v1.13.0.apk" | Select-Object -First 8
"== DONE =="
