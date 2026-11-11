# OpsPilot 演示：业务场景 + 故障注入（响应丢失后自动对账、付款后进程崩溃后自动恢复）。
# 前提：docker compose up -d --build 已经起好。想从头再演示一遍：docker compose down -v 清空数据后重来。
# 用法：.\scripts\demo.ps1
param([string]$BaseUrl = "http://127.0.0.1:8090")

function Api($method, $path, $body) {
    $params = @{ Method = $method; Uri = "$BaseUrl$path"; ContentType = "application/json; charset=utf-8"; TimeoutSec = 10 }
    if ($body) { $params.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json)) }
    try { Invoke-RestMethod @params }
    catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        Write-Host "  → HTTP $code" -ForegroundColor Yellow
        return $null
    }
}

function Wait-App($seconds) {
    for ($i = 0; $i -lt $seconds; $i += 2) {
        try { $h = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 2; if ($h.status -eq "UP") { return $true } } catch {}
        Start-Sleep -Seconds 2
    }
    return $false
}

# 运行在后台执行：轮询直到进入"稳定"状态
function Wait-Run($runId, [string[]]$until = @("COMPLETED", "FAILED", "REJECTED", "WAITING_APPROVAL"), $seconds = 60) {
    for ($i = 0; $i -lt $seconds; $i++) {
        $run = $null
        try { $run = Invoke-RestMethod "$BaseUrl/runs/$runId" -TimeoutSec 2 } catch {}
        if ($run -and $until -contains $run.status) { return $run }
        Start-Sleep -Seconds 1
    }
    return $run
}

function Show-Events($runId) {
    $events = Api GET "/runs/$runId/events"
    foreach ($e in $events) {
        $extra = ""
        if ($e.event_type -eq "run.picked_up") { $extra = "（实例 $($e.payload.instance) 接手）" }
        if ($e.payload.retry_in_seconds) { $extra = "（$($e.payload.retry_in_seconds) 秒后自动重试）" }
        Write-Host ("    {0,2}. {1} {2}" -f $e.sequence, $e.event_type, $extra)
    }
}

function New-Run($request) {
    $task = Api POST "/tasks" @{ request = $request }
    Write-Host "  创建任务 $($task.task_id)，运行 $($task.run_id)"
    return $task.run_id
}

function Payment-Of($po, $inv) {
    try { $p = Invoke-RestMethod "http://127.0.0.1:8103/api/payments/$po/$inv"; return "$($p.id) $($p.status) $($p.amount)" }
    catch { return "没有付款" }
}

if (-not (Wait-App 120)) {
    Write-Host "应用连不上：$BaseUrl/actuator/health 没有响应。" -ForegroundColor Red
    Write-Host "请检查：docker compose ps -a     docker compose logs --tail 60 opspilot" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 场景 1：PO-10002 小额（7600），直接付款 ===" -ForegroundColor Cyan
$r1 = New-Run "帮我处理 PO-10002 的付款"
$started = Api POST "/runs/$r1/start"
Write-Host "  启动接口立即返回：$($started.status)（后台执行中）"
$run = Wait-Run $r1
Write-Host "  最终状态：$($run.status)   付款单：$($run.data.payment.id)"
Show-Events $r1

Write-Host "`n=== 场景 2：PO-10003 采购单 7600 / 发票 7500，金额不一致 ===" -ForegroundColor Cyan
$r2 = New-Run "帮我处理 PO-10003 的付款"
$null = Api POST "/runs/$r2/start"
$run = Wait-Run $r2
Write-Host "  状态：$($run.status)   错误：$($run.error_type)"
$run.data.mismatches | ForEach-Object { Write-Host "    $($_.field)：采购单 $($_.order) / 发票 $($_.invoice)" }

Write-Host "`n=== 场景 3：PO-10001 大额（12800），审批；批准后付款响应丢失 → 自动对账 ===" -ForegroundColor Cyan
$r3 = New-Run "帮我处理 PO-10001 的付款"
$null = Api POST "/runs/$r3/start"
$run = Wait-Run $r3
$approvalId = $run.data.approval_id
Write-Host "  状态：$($run.status)   审批单：$approvalId"
$null = Api POST "/chaos/drop-submit-response?times=1"
Write-Host "  [故障注入] 下一次付款请求：财务系统正常处理，但响应丢弃" -ForegroundColor Magenta
$d = Api POST "/approvals/$approvalId/decision" @{ decision = "approve"; actor = "finance-lead" }
Write-Host "  批准结果：$($d.outcome)"
Write-Host "  等待恢复扫描按退避时间自动重试（不需要手动操作）..."
$run = Wait-Run $r3 @("COMPLETED", "FAILED")
Write-Host "  最终状态：$($run.status)   付款单：$($run.data.payment.id)"
Show-Events $r3
Write-Host "  重复批准：$((Api POST "/approvals/$approvalId/decision" @{ decision = "approve"; actor = "finance-lead" }).outcome)"
Write-Host "  批准后再驳回（应 409）："
$null = Api POST "/approvals/$approvalId/decision" @{ decision = "reject"; actor = "someone-else" }

Write-Host "`n=== 场景 4：PO-10004（5200）付款成功后进程立刻崩溃 → 重启后自动恢复 ===" -ForegroundColor Cyan
$null = Api POST "/chaos/crash-after-submit"
Write-Host "  [故障注入] 下一次付款成功后，进程立即退出（相当于 kill -9）" -ForegroundColor Magenta
$r4 = New-Run "帮我处理 PO-10004 的付款"
$null = Api POST "/runs/$r4/start"
Start-Sleep -Seconds 3
Write-Host "  此刻财务系统里：$(Payment-Of 'PO-10004' 'INV-8924')（钱已经付了，但控制面还没记下来）"
Write-Host "  等待容器自动重启..."
if (-not (Wait-App 120)) { Write-Host "  应用没有重启成功，请看 docker compose logs opspilot" -ForegroundColor Red; exit 1 }
$run = Wait-Run $r4 @("COMPLETED", "FAILED")
Write-Host "  重启后：$($run.status)   付款单：$($run.data.payment.id)"
Show-Events $r4

Write-Host "`n=== 场景 5：PO-10002 再提交一次（重复任务）===" -ForegroundColor Cyan
$r5 = New-Run "PO-10002 好像没付，再付一次"
$null = Api POST "/runs/$r5/start"
$run = Wait-Run $r5
Write-Host "  状态：$($run.status)   付款单：$($run.data.payment.id)（和场景 1 是同一笔）"
Show-Events $r5

Write-Host "`n=== 财务系统里的付款（每个 PO 最多一笔）===" -ForegroundColor Cyan
foreach ($p in @(@("PO-10001", "INV-8921"), @("PO-10002", "INV-8922"), @("PO-10003", "INV-8923"), @("PO-10004", "INV-8924"))) {
    Write-Host "  $($p[0])：$(Payment-Of $p[0] $p[1])"
}
