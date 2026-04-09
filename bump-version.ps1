param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("patch", "minor", "major")]
    [string]$Type
)




# Récupère le dernier tag git de la forme vX.Y.Z
$lastTag = git tag --list "v*.*.*" --sort=-v:refname | Select-Object -First 1
if (-not $lastTag) {
    Write-Error "Aucun tag vX.Y.Z trouvé. Taguez d'abord une version initiale (ex: v0.1.0)."
    exit 1
}
$currentVersion = $lastTag.TrimStart('v')

# Découpe la version
$versionParts = $currentVersion -split '\.'
$major = [int]$versionParts[0]
$minor = [int]$versionParts[1]
$patch = [int]($versionParts[2] -replace "[^0-9].*", "")

switch ($Type) {
    "patch" { $patch++ }
    "minor" { $minor++; $patch = 0 }
    "major" { $major++; $minor = 0; $patch = 0 }
}

$newVersion = "$major.$minor.$patch"

# Push-Location (Split-Path -Path $pomPath -Parent)
# # Applique la version Maven
# $mvnArgs = @("versions:set", "-DnewVersion=$($newVersion)", "-DprocessAllModules=true", "-DgenerateBackupPoms=false")
# mvn @mvnArgs
  
#   Pop-Location
  
# if ($LASTEXITCODE -ne 0) {
#     Write-Error "Erreur lors de la mise à jour Maven. Restauration du pom.xml d'origine."
#     if (Test-Path "$pomPath.versionsBackup") {
#         Move-Item -Force "$pomPath.versionsBackup" $pomPath
#     }
#     exit 1
# }

# Vérifie si le tag existe déjà
$tagName = "v$newVersion"
if (git tag --list $tagName) {
    Write-Error "Le tag $tagName existe déjà. Abandon."
    exit 1
}

# Commit + tag
$commitMsg = "chore: bump version to $newVersion"
git commit -m $commitMsg
git tag $tagName

git push
git push --tags

Write-Host "Version bump to $newVersion done and pushed."
