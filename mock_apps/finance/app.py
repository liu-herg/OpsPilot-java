from __future__ import annotations

import hmac
import os
from contextlib import asynccontextmanager
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from mock_apps.finance.store import PaymentStore


def _benchmark_token() -> str | None:
    return os.getenv("BENCHMARK_INTERNAL_TOKEN") or (
        "test-token" if os.getenv("PYTEST_CURRENT_TEST") else None
    )


def _require_benchmark_token(
    token: str | None = Header(default=None, alias="X-OpsPilot-Benchmark-Token"),
) -> None:
    expected = _benchmark_token()
    if not expected or not token or not hmac.compare_digest(token, expected):
        raise HTTPException(403, "Benchmark state endpoint disabled")


class PaymentCreate(BaseModel):
    po_number: str
    invoice_id: str
    supplier: str
    amount: int = Field(gt=0)
    cost_center: str
    reason: str


class VariantUpdate(BaseModel):
    variant: Literal["v1", "v2"]


class BenchmarkStateUpdate(BaseModel):
    variant: Literal["v1", "v2"]
    payments: dict[str, dict]


def create_app(database_url: str | None = None) -> FastAPI:
    resolved_database_url = (
        database_url or os.getenv("FINANCE_DATABASE_URL") or "sqlite+aiosqlite:///:memory:"
    )
    store = PaymentStore(resolved_database_url)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        await store.initialize()
        try:
            yield
        finally:
            await store.close()

    app = FastAPI(title="FinanceFlow", lifespan=lifespan)
    app.state.payment_store = store
    app.state.variant = os.getenv("UI_VARIANT", "v1")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/benchmark/state")
    async def benchmark_state(_: None = Depends(_require_benchmark_token)) -> dict:
        return {
            "variant": app.state.variant,
            "payments": await store.snapshot(),
        }

    @app.post("/api/benchmark/state")
    async def restore_benchmark_state(
        update: BenchmarkStateUpdate,
        _: None = Depends(_require_benchmark_token),
    ) -> dict[str, str]:
        app.state.variant = update.variant
        await store.restore(update.payments)
        return {"variant": app.state.variant}

    @app.get("/api/variant")
    async def current_variant() -> dict[str, str]:
        return {"variant": app.state.variant}

    @app.post("/api/benchmark/reset")
    async def reset_benchmark_state(
        update: VariantUpdate,
        _: None = Depends(_require_benchmark_token),
    ) -> dict[str, str]:
        await store.reset()
        app.state.variant = update.variant
        return {"variant": app.state.variant}

    @app.post("/api/variant")
    async def variant(update: VariantUpdate) -> dict[str, str]:
        app.state.variant = update.variant
        return {"variant": update.variant}

    @app.post("/api/payments")
    async def create_payment(payload: PaymentCreate, response: Response) -> dict:
        result, created = await store.create_or_get(payload.model_dump())
        if created:
            response.status_code = 201
        return result

    @app.get("/api/payments/{po_number}/{invoice_id}")
    async def payment(po_number: str, invoice_id: str) -> dict:
        result = await store.get(po_number, invoice_id)
        if result is None:
            raise HTTPException(404, "Payment not found")
        return result

    @app.get("/payment/new", response_class=HTMLResponse)
    async def payment_form() -> str:
        v2 = app.state.variant == "v2"
        button = (
            '<button type="button" onclick="confirmPayment()">Confirm Payment</button>'
            if v2
            else '<button type="submit" data-testid="payment-submit">Submit Payment</button>'
        )
        dialog = (
            '<dialog id="confirmation"><p>Confirm this payment request?</p><button id="final-confirm">Confirm</button></dialog>'
            if v2
            else ""
        )
        return f"""<!doctype html><html><head><title>FinanceFlow</title><style>{STYLE}</style></head><body><header>FinanceFlow <small>Treasury Workspace</small><em>UI {app.state.variant}</em></header><main><h1>Create payment request</h1><form id="payment"><label>PO Number<input name="po_number"></label><label>Invoice<input name="invoice_id"></label><label>Supplier<input name="supplier"></label><label>Amount<input name="amount" type="number"></label><label>Cost Center<input name="cost_center" value="IT-OPS"></label><label>Reason<textarea name="reason">Delivered equipment</textarea></label><div class="actions">{button}</div></form>{dialog}<section id="result" aria-live="polite"></section></main><script>{SCRIPT}</script></body></html>"""

    return app


STYLE = "body{margin:0;background:#071b1d;font:16px Arial;color:#d7f7ee}header{padding:22px 7%;border-bottom:1px solid #245052;display:flex;gap:25px}header em{margin-left:auto}main{max-width:760px;margin:35px auto}form{background:#0d292c;padding:28px;border:1px solid #245052;border-radius:14px;display:grid;grid-template-columns:1fr 1fr;gap:18px}label{display:flex;flex-direction:column;gap:8px}input,textarea,button{padding:12px;border-radius:7px;border:1px solid #376467;background:#102f32;color:white}.actions{grid-column:1/-1}button{background:#19b88b;color:#031c17;font-weight:bold}#result{margin-top:20px;padding:20px}"
SCRIPT = """async function send(){const f=new FormData(document.querySelector('#payment'));const p=Object.fromEntries(f.entries());p.amount=Number(p.amount);const r=await fetch('/api/payments',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(p)});const x=await r.json();document.querySelector('#result').innerHTML=`<h2>Payment Status: <span>${x.status}</span></h2><p>${x.id}</p>`;}document.querySelector('#payment').addEventListener('submit',e=>{e.preventDefault();send()});function confirmPayment(){document.querySelector('#confirmation').showModal()}const c=document.querySelector('#final-confirm');if(c)c.onclick=()=>{document.querySelector('#confirmation').close();send()};"""


app = create_app()
