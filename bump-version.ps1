param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("patch", "minor", "major")]
    [string]$Type
)

# Chemin du pom.xml
$pomPath = "agent/pom.xml"

# Récupère la version actuelle
[xml]$pom = Get-Content $pomPath
$currentVersion = $pom.project.version

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

# Met à jour le pom.xml
mvn versions:set -DnewVersion=$newVersion

# Commit + tag
$commitMsg = "chore: bump version to $newVersion"
git add $pomPath
git commit -m $commitMsg
git tag "v$newVersion"

git push
git push --tags

Write-Host "Version bump to $newVersion done and pushed."
