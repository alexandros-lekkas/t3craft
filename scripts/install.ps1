# T3 Craft installer for the official Minecraft Launcher (Windows).
#
#   irm https://github.com/alexandros-lekkas/t3craft/releases/latest/download/install.ps1 | iex
#
# It adds one installation called "T3 Craft" with its own game folder, and touches nothing else:
#   %APPDATA%\.minecraft\versions\fabric-loader-<loader>-<minecraft>\   the Fabric profile (the launcher fetches Fabric itself)
#   %APPDATA%\.minecraft-t3craft\mods\                                  T3 Craft, Fabric API and the light Vanilla+ mods
#   %APPDATA%\.minecraft\launcher_profiles*.json                        one added entry; a backup is kept beside it
# Run it again to update. Set $env:T3CRAFT_MINIMAL = 1 first to install only T3 Craft and Fabric API.
#
# This file is a template: scripts/build-dist.py stamps the @...@ values and writes build/dist/install.ps1.
& {
  $ErrorActionPreference = 'Stop'
  $ProgressPreference = 'SilentlyContinue'
  $version = '@VERSION@'; $jar = '@JAR@'; $jarSha256 = '@JAR_SHA256@'; $minecraft = '@MINECRAFT@'; $loader = '@LOADER@'
  $name = '@NAME@'; $icon = '@ICON@'; $key = 't3craft-modpack'
  $mods = @(
@MODS@
  )
  $fabricProfile = @'
@FABRIC_PROFILE@
'@
  if ($version.StartsWith('@')) { throw 'T3 Craft installer: this is the installer template. Use install.ps1 from a release, or run scripts/build-dist.py.' }
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  $utf8 = New-Object System.Text.UTF8Encoding $false

  $launcher = if ($env:T3CRAFT_MINECRAFT_DIR) { $env:T3CRAFT_MINECRAFT_DIR } else { Join-Path $env:APPDATA '.minecraft' }
  # The Microsoft Store launcher keeps its own profile list beside the classic one.
  $profileFiles = @('launcher_profiles.json', 'launcher_profiles_microsoft_store.json') |
    ForEach-Object { Join-Path $launcher $_ } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
  if (-not $profileFiles) {
    throw "T3 Craft installer: the Minecraft Launcher was not found at $launcher. Install it from https://www.minecraft.net/download, open it once, sign in, close it, and run this again."
  }
  if (-not $env:T3CRAFT_IGNORE_RUNNING_LAUNCHER -and (Get-Process -Name 'MinecraftLauncher', 'Minecraft' -ErrorAction SilentlyContinue)) {
    throw 'T3 Craft installer: the Minecraft Launcher is open, and it would overwrite the new installation when it closes. Close the launcher and run this again.'
  }

  # Parsing every profile list first proves the files are readable before anything is written.
  $documents = @{}
  $game = $null
  foreach ($file in $profileFiles) {
    try { $document = [IO.File]::ReadAllText($file) | ConvertFrom-Json } catch { throw "T3 Craft installer: could not read $file; nothing was changed." }
    if ($null -eq $document -or $document -isnot [pscustomobject]) { throw "T3 Craft installer: could not read $file; nothing was changed." }
    if ($null -eq $document.PSObject.Properties['profiles']) { $document | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) }
    $existing = $document.profiles.PSObject.Properties[$key]
    if (-not $game -and $existing -and $existing.Value.PSObject.Properties['gameDir']) { $game = [string]$existing.Value.gameDir }
    $documents[$file] = $document
  }
  if ($env:T3CRAFT_GAME_DIR) { $game = $env:T3CRAFT_GAME_DIR } elseif (-not $game) { $game = "$launcher-t3craft" }

  # Download and verify everything before touching the launcher or the game folder.
  $staging = Join-Path ([IO.Path]::GetTempPath()) ('t3craft-install-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $staging | Out-Null
  try {
    function Get-Verified($url, $target, $algorithm, $expected) {
      try { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $target } catch { throw "T3 Craft installer: could not download $url" }
      if ((Get-FileHash -LiteralPath $target -Algorithm $algorithm).Hash -ne $expected) {
        throw "T3 Craft installer: $(Split-Path -Leaf $target) does not match its pinned checksum; nothing was installed."
      }
    }
    Write-Host "Installing T3 Craft $version for Minecraft $minecraft (Fabric Loader $loader)"
    $jarUrl = if ($env:T3CRAFT_JAR_URL) { $env:T3CRAFT_JAR_URL } else { "https://github.com/alexandros-lekkas/t3craft/releases/download/v$version/$jar" }
    Get-Verified $jarUrl (Join-Path $staging $jar) 'SHA256' $jarSha256
    $wanted = @($mods | Where-Object { $_.Required -or -not $env:T3CRAFT_MINIMAL })
    foreach ($mod in $wanted) {
      $url = $mod.Url
      if ($env:T3CRAFT_MODRINTH_CDN) { $url = $env:T3CRAFT_MODRINTH_CDN + $url.Substring('https://cdn.modrinth.com'.Length) }
      Get-Verified $url (Join-Path $staging $mod.File) 'SHA512' $mod.Sha512
    }

    $id = "fabric-loader-$loader-$minecraft"
    $versionDir = Join-Path (Join-Path $launcher 'versions') $id
    $modsDir = Join-Path $game 'mods'
    New-Item -ItemType Directory -Force -Path $versionDir, $modsDir | Out-Null
    [IO.File]::WriteAllText((Join-Path $versionDir "$id.json"), $fabricProfile + "`n", $utf8)

    # Replace only the files this installer manages; mods you added yourself stay.
    Get-ChildItem -LiteralPath $modsDir -Filter 't3craft-*.jar' | Remove-Item -Force
    Move-Item -LiteralPath (Join-Path $staging $jar) -Destination (Join-Path $modsDir $jar) -Force
    foreach ($mod in $wanted) {
      Get-ChildItem -LiteralPath $modsDir -Filter $mod.Replaces | Remove-Item -Force
      Move-Item -LiteralPath (Join-Path $staging $mod.File) -Destination (Join-Path $modsDir $mod.File) -Force
    }

    $now = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fff') + 'Z'
    foreach ($file in $profileFiles) {
      $document = $documents[$file]
      $existing = $document.profiles.PSObject.Properties[$key]
      if ($existing) { $entry = $existing.Value } else {
        $entry = [pscustomobject]@{ created = $now; icon = $icon; name = $name; type = 'custom' }
        $document.profiles | Add-Member -NotePropertyName $key -NotePropertyValue $entry
      }
      $entry | Add-Member -NotePropertyName gameDir -NotePropertyValue $game -Force
      $entry | Add-Member -NotePropertyName lastVersionId -NotePropertyValue $id -Force
      $entry | Add-Member -NotePropertyName lastUsed -NotePropertyValue $now -Force
      Copy-Item -LiteralPath $file -Destination "$file.t3craft-backup" -Force
      [IO.File]::WriteAllText("$file.t3craft-tmp", ($document | ConvertTo-Json -Depth 64) + "`n", $utf8)
      Move-Item -LiteralPath "$file.t3craft-tmp" -Destination $file -Force
    }
  } finally {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
  }

  Write-Host ''
  Write-Host "T3 Craft $version is installed."
  Write-Host "  Game folder: $game"
  Write-Host ''
  Write-Host '  1. Open T3 Code.'
  Write-Host "  2. Open the Minecraft Launcher, choose `"$name`" next to the Play button, and press Play."
  Write-Host '  3. Click "T3 World" on the title screen.'
}
