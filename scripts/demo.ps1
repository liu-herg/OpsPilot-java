# OpsPilot 演示：采购到付款全流程 + 故障注入（响应丢失后自动对账、付款后进程崩溃后自动恢复）
#              + 消息队列（两个实例分摊执行、RabbitMQ 停机时降级到恢复扫描）。
# 前提：docker compose up -d --build 已经起好（首次启动会写入 PO-10001 ~ PO-10004 的演示单据）。
# 想从头再演示一遍：docker compose down -v 清空数据后重来。
# 用法：.\scripts\demo.ps1
param([string]$BaseUrl = "http://127.0.0.1:8090", [string]$SecondUrl = "http://127.0.0.1:8091")

# docker compose 命令要在项目根目录执行
Set-Location (Split-Path $PSScriptRoot -Parent)

function Api($method, $path, $body, [switch]$Quiet) {
    $params = @{ Method = $method; Uri = "$BaseUrl$path"; ContentType = "application/json; charset=utf-8"; TimeoutSec = 15 }
    if ($body) { $params.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6)) }
    try { Invoke-RestMethod @params }
    catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $msg = ""
        if ($_.ErrorDetails.Message) { try { $msg = ($_.ErrorDetails.Message | ConvertFrom-Json).message } catch {} }
        if (-not $Quiet) { Write-Host "  → HTTP $code $msg" -ForegroundColor Yellow }
        return $null
    }
}

function Wait-App($seconds, $url = $BaseUrl) {
    for ($i = 0; $i -lt $seconds; $i += 2) {
        try { $h = Invoke-RestMethod "$url/actuator/health" -TimeoutSec 2; if ($h.status -eq "UP") { return $true } } catch {}
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
        if ($e.event_type -eq "run.picked_up") {
            $how = if ($e.payload.by -eq "mq") { "从 RabbitMQ 消费" } else { "恢复扫描接手" }
            $extra = "（实例 $($e.payload.instance) $how）"
        }
        if ($e.payload.retry_in_seconds) { $extra = "（$($e.payload.retry_in_seconds) 秒后自动重试）" }
        Write-Host ("    {0,2}. {1} {2}" -f $e.sequence, $e.event_type, $extra)
    }
}

function New-Run($request) {
    $task = Api POST "/tasks" @{ request = $request }
    if ($task) { Write-Host "  创建任务 $($task.task_id)，运行 $($task.run_id)" }
    return $task.run_id
}

function Picked-By($runId) {
    $e = (Api GET "/runs/$runId/events") | Where-Object { $_.event_type -eq "run.picked_up" } | Select-Object -First 1
    if ($e) { return "$($e.payload.instance)/$($e.payload.by)" } else { return "?" }
}

function Outbox-Stats() {
    $s = Api GET "/outbox/stats"
    return "待投递 $($s.outbox.PENDING)，已投递 $($s.outbox.SENT)，放弃 $($s.outbox.DEAD)"
}

function Payment-Of($po, $inv) {
    try { $p = Invoke-RestMethod "http://127.0.0.1:8103/api/payments/$po/$inv"; return "$($p.id) $($p.status) $($p.amount)" }
    catch { return "没有付款" }
}

function Supplier-Id($keyword) {
    return (Api GET "/suppliers?keyword=$keyword").items[0].id
}

# 走一遍正常业务链，造出一张待付款的付款单：申请 → 审批 → 转订单 → 收货 → 登记发票（三单匹配）
function New-Payable($supplierId, $title, $price, $tag) {
    $pr = Api POST "/purchase-requests" @{ department = "行政部"; requester = "张敏"; title = $title
        lines = @(@{ item_name = $title; quantity = 1; unit = "批"; est_unit_price = $price }) } -Quiet
    $null = Api POST "/purchase-requests/$($pr.id)/submit" -Quiet
    $null = Api POST "/purchase-requests/$($pr.id)/approve" @{ actor = "部门经理-李响" } -Quiet
    $po = Api POST "/purchase-requests/$($pr.id)/order" @{ supplier_id = $supplierId; buyer = "采购-王芳" } -Quiet
    $null = Api POST "/purchase-orders/$($po.id)/receipts" @{ receiver = "仓库-李强"; lines = @(@{ line_no = 1; quantity = 1 }) } -Quiet
    $inv = Api POST "/invoices" @{ invoice_number = "INV-$tag"; supplier_id = $supplierId; po_number = $po.po_number
        invoice_date = (Get-Date -Format "yyyy-MM-dd"); amount = $price; tax_amount = 0; total_amount = $price
        registered_by = "财务-陈静"; lines = @(@{ line_no = 1; quantity = 1; unit_price = $price }) } -Quiet
    $pay = (Api GET "/payment-orders?status=PENDING&size=100" -Quiet).items | Where-Object { $_.invoice_number -eq $inv.invoice_number }
    return $pay
}

if (-not (Wait-App 120)) {
    Write-Host "应用连不上：$BaseUrl/actuator/health 没有响应。" -ForegroundColor Red
    Write-Host "请检查：docker compose ps -a     docker compose logs --tail 60 opspilot" -ForegroundColor Red
    exit 1
}
$run_tag = Get-Date -Format "HHmmss"

Write-Host "`n=== 场景 0：从采购申请到付款（申请 → 审批 → 订单 → 分批收货 → 发票三单匹配 → 付款）===" -ForegroundColor Cyan
$nova = Supplier-Id "Nova"
$pr = Api POST "/purchase-requests" @{ department = "行政部"; requester = "张敏"; title = "会议室显示器"
    lines = @(@{ item_name = "27 寸显示器"; spec = "4K"; quantity = 4; unit = "台"; est_unit_price = 1200 },
              @{ item_name = "HDMI 线"; quantity = 4; unit = "根"; est_unit_price = 35.5 }) }
Write-Host "  采购申请 $($pr.request_no)：$($pr.title)，预估 $($pr.total_amount) 元，状态 $($pr.status)"
$null = Api POST "/purchase-requests/$($pr.id)/submit"
Write-Host "  申请人本人审批（应被拒绝）："
$null = Api POST "/purchase-requests/$($pr.id)/approve" @{ actor = "张敏" }
$pr = Api POST "/purchase-requests/$($pr.id)/approve" @{ actor = "部门经理-李响" }
Write-Host "  部门经理审批：$($pr.status)"
$po = Api POST "/purchase-requests/$($pr.id)/order" @{ supplier_id = $nova; buyer = "采购-王芳"; prices = @(@{ line_no = 1; unit_price = 1150 }) }
Write-Host "  转采购订单 $($po.po_number)：显示器谈到 1150，订单金额 $($po.total_amount)，状态 $($po.status)"
$g1 = Api POST "/purchase-orders/$($po.id)/receipts" @{ receiver = "仓库-李强"; lines = @(@{ line_no = 1; quantity = 2 }, @{ line_no = 2; quantity = 4 }) }
Write-Host "  第一批到货 $($g1.gr_number)：显示器 2 台、线 4 根 → 订单 $($g1.order.status)"
Write-Host "  第二批多收 3 台（只剩 2 台未到，应被拒绝）："
$null = Api POST "/purchase-orders/$($po.id)/receipts" @{ receiver = "仓库-李强"; lines = @(@{ line_no = 1; quantity = 3 }) }
$g2 = Api POST "/purchase-orders/$($po.id)/receipts" @{ receiver = "仓库-李强"; lines = @(@{ line_no = 1; quantity = 2 }) }
Write-Host "  第二批到货 $($g2.gr_number)：显示器 2 台 → 订单 $($g2.order.status)"
$inv = Api POST "/invoices" @{ invoice_number = "INV-S0-$run_tag"; supplier_id = $nova; po_number = $po.po_number
    invoice_date = (Get-Date -Format "yyyy-MM-dd"); amount = 4742; tax_amount = 616.46; total_amount = 5358.46
    registered_by = "财务-陈静"; lines = @(@{ line_no = 1; quantity = 4; unit_price = 1150 }, @{ line_no = 2; quantity = 4; unit_price = 35.5 }) }
Write-Host "  登记发票 $($inv.invoice_number)：价税合计 $($inv.total_amount) → 三单匹配 $($inv.status)，付款单 $($inv.pay_number)"
$pay = (Api GET "/payment-orders?status=PENDING&size=100").items | Where-Object { $_.pay_number -eq $inv.pay_number }
$pay = Api POST "/payment-orders/$($pay.id)/pay"
$run = Wait-Run $pay.run_id @("COMPLETED", "FAILED")
$pay = Api GET "/payment-orders/$($pay.id)"
Write-Host "  发起付款：运行 $($pay.run_id) → $($run.status)；付款单 $($pay.status)，财务付款号 $($pay.finance_payment_id)"
Write-Host "  订单最终状态：$((Api GET "/purchase-orders/$($po.id)").status)"

Write-Host "`n=== 场景 1：PO-10002 小额（7600），快捷入口发起付款 ===" -ForegroundColor Cyan
$r1 = New-Run "帮我处理 PO-10002 的付款"
$started = Api POST "/runs/$r1/start"
Write-Host "  启动接口立即返回：$($started.status)（后台执行中）"
$run = Wait-Run $r1
Write-Host "  最终状态：$($run.status)   付款单：$($run.data.payment.id)"
Show-Events $r1

Write-Host "`n=== 场景 2：PO-10003 订单单价 7600、发票单价 7500 → 三单匹配不通过 ===" -ForegroundColor Cyan
$i3 = (Api GET "/invoices?status=EXCEPTION").items | Where-Object { $_.po_number -eq "PO-10003" } | Select-Object -First 1
$i3 = Api GET "/invoices/$($i3.id)"
Write-Host "  发票 $($i3.invoice_number)：$($i3.status)，付款单：$(if ($i3.pay_number) { $i3.pay_number } else { '没有生成' })"
$i3.exceptions | ForEach-Object { Write-Host "    差异 $($_.type)：订单 $($_.detail.order_price) / 发票 $($_.detail.invoice_price)，偏差 $($_.detail.deviation)（容差 $($_.detail.tolerance)）" }
Write-Host "  这时说'处理 PO-10003 的付款'（应 409）："
$null = Api POST "/tasks" @{ request = "帮我处理 PO-10003 的付款" }
$i3 = Api POST "/match-exceptions/$($i3.exceptions[0].id)/reject" @{ actor = "财务-陈静" }
Write-Host "  财务驳回差异，退回供应商重开：发票 $($i3.status)"

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

Write-Host "`n=== 场景 5：PO-10002 再发起一次（重复付款）===" -ForegroundColor Cyan
Write-Host "  'PO-10002 好像没付，再付一次'（付款单已经 PAID，应 409）："
$null = Api POST "/tasks" @{ request = "PO-10002 好像没付，再付一次" }
Write-Host "  业务层先拦住；就算绕过业务层，付款幂等记录和财务对账也保证只付一次（P1 测试覆盖）"

Write-Host "`n=== 财务系统里的付款（每张发票最多一笔）===" -ForegroundColor Cyan
foreach ($p in @(@("PO-10001", "INV-8921"), @("PO-10002", "INV-8922"), @("PO-10003", "INV-8923"), @("PO-10004", "INV-8924"))) {
    Write-Host "  $($p[0])：$(Payment-Of $p[0] $p[1])"
}
Write-Host "  付款单：$(((Api GET '/payment-orders?status=PAID&size=100').total)) 张已付款"

Write-Host "`n=== 场景 6：再起一个实例，20 张付款单由两个实例一起执行 ===" -ForegroundColor Cyan
docker compose --profile multi up -d opspilot-2 2>&1 | Out-Null
if (-not (Wait-App 120 $SecondUrl)) { Write-Host "  第二个实例没起来，请看 docker compose logs opspilot-2" -ForegroundColor Red; exit 1 }
Start-Sleep -Seconds 3
Write-Host "  先走正常业务链造 20 张待付款的付款单（每张 300~1250 元）..."
$pays = @()
foreach ($i in 1..20) { $pays += New-Payable $nova "办公耗材 第 $i 批" (250 + 50 * $i) "S6-$run_tag-$i" }
Write-Host "  20 张付款单全部从实例 app-1（8090）发起..."
$runIds = @()
foreach ($p in $pays) { $runIds += (Api POST "/payment-orders/$($p.id)/pay").run_id }
$done = 0
for ($t = 0; $t -lt 90 -and $done -lt 20; $t++) {
    Start-Sleep -Seconds 1
    $done = @($pays | Where-Object { (Api GET "/payment-orders/$($_.id)").status -eq "PAID" }).Count
}
Write-Host "  已付款 $done / 20"
$runIds | ForEach-Object { Picked-By $_ } | Group-Object | Sort-Object Name | ForEach-Object {
    Write-Host ("    {0}：{1} 个" -f $_.Name, $_.Count)
}
Write-Host "  （P2 是哪个实例收到请求就由哪个实例执行；现在消息进 RabbitMQ，谁空闲谁消费）"
docker compose stop opspilot-2 2>&1 | Out-Null

Write-Host "`n=== 场景 7：RabbitMQ 停机 → 运行由恢复扫描执行；恢复后消息补发、被识别为重复 ===" -ForegroundColor Cyan
$p7 = New-Payable $nova "打印纸" 480 "S7-$run_tag"
docker compose stop rabbitmq 2>&1 | Out-Null
Write-Host "  [故障] RabbitMQ 已停止" -ForegroundColor Magenta
$skippedBefore = (Api GET "/outbox/stats").consumed_skipped
$p7 = Api POST "/payment-orders/$($p7.id)/pay"
Write-Host "  发起付款照常返回：付款单 $($p7.status)"
Start-Sleep -Seconds 2
Write-Host "  发件箱：$(Outbox-Stats)"
Write-Host "  消息投不出去；等 15 秒宽限期过后，由恢复扫描兜底执行..."
$run = Wait-Run $p7.run_id @("COMPLETED", "FAILED")
Write-Host "  最终状态：$($run.status)   执行者：$(Picked-By $p7.run_id)   付款单：$((Api GET "/payment-orders/$($p7.id)").status)"
docker compose start rabbitmq 2>&1 | Out-Null
Write-Host "  [恢复] RabbitMQ 已启动，等发件箱补发..." -ForegroundColor Magenta
for ($t = 0; $t -lt 120; $t += 3) {
    Start-Sleep -Seconds 3
    $s = Api GET "/outbox/stats" -Quiet
    if ($s -and $s.outbox.PENDING -eq 0 -and $s.consumed_skipped -gt $skippedBefore) { break }
}
Write-Host "  发件箱：$(Outbox-Stats)"
Write-Host "  补发的消息被消费者跳过：$($s.consumed_skipped - $skippedBefore) 条（运行已经完成，不会重复执行）"
