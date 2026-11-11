from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse

from mock_apps.shared import INVOICES


def create_app() -> FastAPI:
    app = FastAPI(title="InvoiceCenter")

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/api/invoices/by-po/{po_number}")
    async def invoice(po_number: str) -> dict:
        if po_number not in INVOICES:
            raise HTTPException(404, "Invoice not found")
        return INVOICES[po_number]

    @app.get("/invoices", response_class=HTMLResponse)
    async def invoices(po: str = "PO-10001") -> str:
        item = INVOICES.get(po)
        content = _card(item) if item else "<p>Invoice not found</p>"
        return f'''<!doctype html><html><head><title>InvoiceCenter</title><style>{STYLE}</style></head><body><aside>InvoiceCenter<br><small>Accounts Payable</small></aside><main><h1>Invoice validation</h1><form><label>Purchase order<input name="po" aria-label="Purchase order" value="{po}"></label><button>Find invoice</button></form>{content}</main></body></html>'''

    return app


def _card(item: dict) -> str:
    return f"""<article><span>{item["status"]}</span><h2 data-semantic="invoice_id">{item["invoice_id"]}</h2><p>PO <b>{item["po_id"]}</b></p><p>Supplier <b data-semantic="invoice_supplier">{item["supplier"]}</b></p><p>Amount <b data-semantic="invoice_amount">{item["amount"]}</b> CNY</p></article>"""


STYLE = "body{margin:0;background:#f7f5ff;font:16px Arial;color:#251b42}aside{position:fixed;width:190px;inset:0 auto 0 0;padding:28px;background:#4c2ca0;color:white}main{margin-left:240px;max-width:760px;padding:45px}form,article{background:white;padding:25px;border-radius:14px;margin:20px 0;box-shadow:0 8px 30px #43278b17}input,button{padding:12px;margin:8px;border-radius:7px;border:1px solid #c6bde0}button{background:#6b3dd1;color:white}article span{float:right;background:#e1d7ff;padding:6px 10px;border-radius:20px}"


app = create_app()
