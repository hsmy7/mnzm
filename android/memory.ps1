# Elite Long-term Memory System CLI Wrapper
# 精英长期记忆系统命令行工具

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$memoryDir = Join-Path $scriptDir ".trae\memory"
$pythonScript = Join-Path $memoryDir "cli\memory_cli.py"

if ($args.Count -eq 0) {
    python $pythonScript --help
} else {
    python $pythonScript @args
}
