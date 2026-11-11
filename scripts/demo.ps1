# OpsPilot 演示：三个业务场景 + 故障注入（付款响应丢失后对账）。
# 前提：docker compose up -d --build 已经起好。想从头再演示一遍：docker compose down -v 清空数据后重来。
# 用法：.\scripts\demo.ps1
param([string]$BaseUrl = "http://127.0.0.1:8090")

function Api($method, $path, $body) {
    $params = @{ Method = $method; Uri = "$BaseUrl$path"; ContentType = "application/json; charset=utf-8" }
    if ($body) { $params.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json)) }
    try { Invoke-RestMethod @params }
    catch {
        $code = [int]$_.Exception.Response.StatusCode
        $detail = $_.ErrorDetails.Message
        Write-Host "  → HTTP $code $detail" -ForegroundColor Yellow
        return $null
    }
}

function Show-Events($runId) {
    $events = Api GET "/runs/$runId/events"
    foreach ($e in $events) { Write-Host ("    {0,2}. {1}" -f $e.sequence, $e.event_type) }
}

function New-Run($request) {
    $task = Api POST "/tasks" @{ request = $request }
    Write-Host "  创建任务 $($task.task_id)，运行 $($task.run_id)"
    return $task.run_id
}

function Submit-Count() {
    $m = Api GET "/actuator/metrics/opspilot.payment.submit.requests"
    if ($m) { return [int]$m.measurements[0].value } else { return 0 }
}

# 先确认应用已经启动（第一次 --build 编译需要几分钟）
$ready = $false
for ($i = 0; $i -lt 24; $i++) {
    try { $h = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3; if ($h.status -eq "UP") { $ready = $true; break } } catch {}
    if ($i -eq 0) { Write-Host "等待应用启动（$BaseUrl）..." }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    Write-Host "应用连不上：$BaseUrl/actuator/health 没有响应。" -ForegroundColor Red
    Write-Host "请检查：docker compose ps -a     docker compose logs --tail 60 opspilot" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 场景 1：PO-10002 小额（7600），直接付款 ===" -ForegroundColor Cyan
$r1 = New-Run "帮我处理 PO-10002 的付款"
$run = Api POST "/runs/$r1/start"
Write-Host "  状态：$($run.status)   付款单：$($run.data.payment.id)"
Show-Events $r1

Write-Host "`n=== 场景 2：PO-10003 采购单 7600 / 发票 7500，金额不一致 ===" -ForegroundColor Cyan
$r2 = New-Run "帮我处理 PO-10003 的付款"
$run = Api POST "/runs/$r2/start"
Write-Host "  状态：$($run.status)   错误：$($run.error_type)"
$run.data.mismatches | ForEach-Object { Write-Host "    $($_.field)：采购单 $($_.order) / 发票 $($_.invoice)" }

Write-Host "`n=== 场景 3：PO-10001 大额（12800），需要审批；批准时让付款响应丢失 ===" -ForegroundColor Cyan
$r3 = New-Run "帮我处理 PO-10001 的付款"
$run = Api POST "/runs/$r3/start"
$approvalId = $run.data.approval_id
Write-Host "  状态：$($run.status)   审批单：$approvalId"
Write-Host "  待审批列表：$((Api GET '/approvals') | ForEach-Object { $_.id })"

$null = Api POST "/chaos/drop-submit-response?times=1"
Write-Host "  [故障注入] 下一次付款请求：财务系统正常处理，但响应丢弃" -ForegroundColor Magenta
$before = Submit-Count
$d = Api POST "/approvals/$approvalId/decision" @{ decision = "approve"; actor = "finance-lead" }
Write-Host "  批准结果：$($d.outcome)   运行状态：$($d.run.status)（结果不确定，停在待提交）"

$run = Api POST "/runs/$r3/resume"
Write-Host "  继续执行后：$($run.status)   付款单：$($run.data.payment.id)"
Write-Host "  这一笔一共发了 $((Submit-Count) - $before) 次付款请求（对账发现已付款，没有再发）"
Show-Events $r3

Write-Host "`n  重复批准（幂等）："
$d = Api POST "/approvals/$approvalId/decision" @{ decision = "approve"; actor = "finance-lead" }
Write-Host "    $($d.outcome)"
Write-Host "  已批准后再驳回（应 409）："
$null = Api POST "/approvals/$approvalId/decision" @{ decision = "reject"; actor = "someone-else" }

Write-Host "`n=== 场景 4：PO-10002 再提交一次（重复任务）===" -ForegroundColor Cyan
$before = Submit-Count
$r4 = New-Run "PO-10002 好像没付，再付一次"
$run = Api POST "/runs/$r4/start"
Write-Host "  状态：$($run.status)   付款单：$($run.data.payment.id)（和场景 1 是同一笔）"
Write-Host "  发出的付款请求：$((Submit-Count) - $before) 次"
Show-Events $r4

Write-Host "`n=== 财务系统里的付款 ===" -ForegroundColor Cyan
foreach ($p in @(@("PO-10001", "INV-8921"), @("PO-10002", "INV-8922"), @("PO-10003", "INV-8923"))) {
    try {
        $pay = Invoke-RestMethod "http://127.0.0.1:8103/api/payments/$($p[0])/$($p[1])"
        Write-Host "  $($p[0])：$($pay.id) $($pay.status) $($pay.amount)"
    } catch { Write-Host "  $($p[0])：没有付款" }
}
