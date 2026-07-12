param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Target,

    [Parameter(Position = 1)]
    [string] $Task = "build"
)

$wrapper = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path -LiteralPath $wrapper)) {
    throw "Missing Forge Gradle wrapper: $wrapper"
}

$gradleTask = ":" + $Target + ":" + $Task
& java -classpath $wrapper org.gradle.wrapper.GradleWrapperMain $gradleTask
exit $LASTEXITCODE
