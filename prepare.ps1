param([switch]$TestPack)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -Force -ItemType Directory .deps | Out-Null
    $files = @{
        'drivebywire' = 'https://cdn.modrinth.com/data/KsqvI0hD/versions/zdSwg9kS/drivebywire-0.3.0.jar'
        'sable' = 'https://cdn.modrinth.com/data/T9PomCSv/versions/fg9dTRz9/sable-neoforge-1.21.1-2.0.6.jar'
        'ntmspace' = 'https://mediafilez.forgecdn.net/files/9524/269/ntmspace-neoforge-1.0.2-1.21.1.jar'
        'hbm' = 'https://mediafilez.forgecdn.net/files/9524/60/hbm-neoforge-1.0.5-1.21.1.jar'
        'golemcore' = 'https://mediafilez.forgecdn.net/files/9523/847/golemcore-neoforge-1.0.5-1.21.1.jar'
    }
    if ($TestPack) {
        $files += @{
            "typewriter" = "https://cdn.modrinth.com/data/lDYpnxit/versions/Zu7emlh1/drivebywire-typewriter-1.2.0.jar"
            "create" = "https://mediafilez.forgecdn.net/files/7963/363/create-1.21.1-6.0.10.jar"
            "aero" = "https://cdn.modrinth.com/data/oWaK0Q19/versions/44pLdPGg/create-aeronautics-bundled-1.21.1-1.3.2.jar"
            "gemrender" = "https://mediafilez.forgecdn.net/files/9668/87/gemrender-0.1.22.jar"
            "hbmsable" = "https://mediafilez.forgecdn.net/files/9629/828/hbm-continuum-x-sable-1.21.1-1.4.jar"
            "jei" = "https://mediafilez.forgecdn.net/files/9318/777/jei-1.21.1-neoforge-19.70.0.458.jar"
            "mezzconfig" = "https://cdn.modrinth.com/data/7tEfOcA7/versions/sPQlt1iR/mezz_config-1.21.1-neoforge-0.6.11-standalone.jar"
            "iris-pack" = "https://cdn.modrinth.com/data/YL57xq9U/versions/KduFYu4t/iris-neoforge-1.8.14-beta.1%2Bmc1.21.1.jar"
            "sodium-pack" = "https://cdn.modrinth.com/data/AANobbMI/versions/uMOpc5uV/sodium-neoforge-0.8.13%2Bmc1.21.1.jar"
        }
    }
    foreach ($name in $files.Keys) {
        $dest = ".deps/$name.jar"
        if (!(Test-Path $dest)) {
            curl.exe -fLsS --retry 3 $files[$name] -o "$dest.tmp"
            if ($LASTEXITCODE) { throw "Dependency download failed for $name" }
            Move-Item "$dest.tmp" $dest
        }
    }
    if ($TestPack) {
        foreach ($directory in "run/mods","client-run/mods") {
            New-Item -Force -ItemType Directory $directory | Out-Null
            foreach ($name in $files.Keys) {
                if ($directory -eq "run/mods" -and $name -in "iris-pack","sodium-pack") { continue }
                $source = ".deps/$name.jar"
                $hash = (Get-FileHash $source -Algorithm SHA1).Hash
                $exists = Get-ChildItem $directory -Filter *.jar | Where-Object { (Get-FileHash $_.FullName -Algorithm SHA1).Hash -eq $hash } | Select-Object -First 1
                if (!$exists) { Copy-Item $source "$directory/$name.jar" }
            }
        }
    }
    Push-Location .deps
    try {
        if ($TestPack) { jar xf sable.jar META-INF/jarjar/veil-neoforge-1.21.1-4.3.2.jar; jar xf create.jar META-INF/jarjar; jar xf aero.jar META-INF/jarjar }
        jar xf sable.jar META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar } finally { Pop-Location }
} finally { Pop-Location }
