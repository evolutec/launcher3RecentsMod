# Build manuel du module Vector (pas de Gradle sur ce poste).
# javac -> d8 -> aapt2 link -> ajout classes.dex -> zipalign -> apksigner
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\36.0.0"
$androidJar = "$sdk\platforms\android-37.0\android.jar"
$xposedJar  = "$root\xposed-api-82.jar"
$jdk  = "C:\Program Files\Java\jdk-25"

$src      = "$root\app\src\main\java"
$assets   = "$root\app\src\main\assets"
$manifest = "$root\app\src\main\AndroidManifest.xml"

$out      = "$root\build"
$classes  = "$out\classes"
$dex      = "$out\dex"
$keystore = "$out\module.keystore"
$final    = "$out\launcher3mod-pixel.apk"

Remove-Item -Recurse -Force $classes, $dex -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null

Write-Host "[1/6] javac"
$sources = Get-ChildItem -Recurse -Filter *.java $src | Select-Object -ExpandProperty FullName
& javac --release 11 -encoding UTF-8 -cp "$androidJar;$xposedJar" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac a echoue" }

Write-Host "[2/6] d8"
$classFiles = Get-ChildItem -Recurse -Filter *.class $classes | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --min-api 31 --lib "$androidJar" --lib "$xposedJar" --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 a echoue" }

Write-Host "[3/6] aapt2 link"
$base = "$out\base.apk"
& "$bt\aapt2.exe" link -o $base -I "$androidJar" --manifest "$manifest" -A "$assets" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link a echoue" }

Write-Host "[4/6] ajout classes.dex"
Copy-Item "$dex\classes.dex" "$out\classes.dex" -Force
& "$jdk\bin\jar.exe" uf "$base" -C "$out" classes.dex
if ($LASTEXITCODE -ne 0) { throw "ajout classes.dex a echoue" }

Write-Host "[5/6] zipalign"
$aligned = "$out\aligned.apk"
& "$bt\zipalign.exe" -f 4 "$base" "$aligned"
if ($LASTEXITCODE -ne 0) { throw "zipalign a echoue" }

Write-Host "[6/6] signature"
if (!(Test-Path $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -v -keystore "$keystore" -alias module -keyalg RSA -keysize 2048 -validity 10000 -storepass bricks -keypass bricks -dname "CN=Launcher3Mod"
    if ($LASTEXITCODE -ne 0) { throw "keytool a echoue" }
}
& "$bt\apksigner.bat" sign --ks "$keystore" --ks-key-alias module --ks-pass pass:bricks --key-pass pass:bricks --out "$final" "$aligned"
if ($LASTEXITCODE -ne 0) { throw "apksigner a echoue" }

Write-Host ""
Write-Host "OK -> $final"
& "$bt\apksigner.bat" verify --print-certs "$final"
Write-Host ("Taille : " + [math]::Round((Get-Item $final).Length / 1KB, 1) + " Ko")