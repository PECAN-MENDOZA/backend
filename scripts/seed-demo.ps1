param(
    [switch]$Reset = $true,
    [int]$StudentCount = 15
)

$arguments = @(
    "--app.demo-seed.enabled=true",
    "--app.demo-seed.exit-after-run=true",
    "--app.demo-seed.student-count=$StudentCount",
    "--server.port=0"
)

if ($Reset) {
    $arguments += "--app.demo-seed.reset=true"
}

./mvnw spring-boot:run "-Dspring-boot.run.arguments=$($arguments -join ' ')"
