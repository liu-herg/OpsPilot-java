from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse

from mock_apps.shared import ORDERS


def create_app() -> FastAPI:
    app = FastAPI(title="ProcureHub")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/orders/{po_number}")
    async def order(po_number: str) -> dict:
        if po_number not in ORDERS:
            raise HTTPException(404, "Purchase order not found")
        return ORDERS[po_number]

    @app.get("/orders", response_class=HTMLResponse)
    async def orders(po: str = "PO-10001") -> str:
        item = ORDERS.get(po)
        detail = _order_card(item) if item else '<p class="error">Order not found</p>'
        return _page("ProcureHub", po, detail)

    @app.get("/orders/{po_number}", response_class=HTMLResponse)
    async def order_page(po_number: str) -> str:
        item = ORDERS.get(po_number)
        if not item:
            raise HTTPException(404, "Purchase order not found")
        return _page("ProcureHub", po_number, _order_card(item))

    return app


def _order_card(item: dict) -> str:
    return f"""<section class="card" aria-label="Purchase order details">
      <span class="badge">{item["status"]}</span><h2 data-testid="po-number">{item["id"]}</h2>
      <dl><dt>Supplier</dt><dd data-semantic="po_supplier">{item["supplier"]}</dd>
      <dt>Amount</dt><dd data-semantic="po_amount">{item["amount"]}</dd>
      <dt>Currency</dt><dd>{item["currency"]}</dd></dl></section>"""


def _page(title: str, po: str, body: str) -> str:
    return f'''<!doctype html><html><head><title>{title}</title><style>{STYLE}</style></head><body>
    <header><strong>ProcureHub</strong><span>Procurement Operations</span></header><main>
    <h1>Purchase Orders</h1><form action="/orders"><label>PO Number<input data-testid="po-search" name="po" value="{po}"></label><button>Search Order</button></form>{body}</main></body></html>'''


STYLE = "body{margin:0;background:#eef2f6;font:16px Arial;color:#172033}header{padding:20px 8%;background:#172033;color:white;display:flex;justify-content:space-between}main{max-width:900px;margin:50px auto}.card,form{background:white;padding:24px;border-radius:12px;margin:20px 0;box-shadow:0 8px 30px #21314a18}input,button{padding:12px;margin:8px;border-radius:7px;border:1px solid #b7c0ce}button{background:#3157d5;color:white}.badge{float:right;background:#c8f5d8;padding:6px 10px;border-radius:20px}dt{color:#687386;margin-top:12px}dd{font-weight:bold;margin:3px 0}"


app = create_app()
