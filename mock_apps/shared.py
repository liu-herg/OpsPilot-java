from typing import Any

ORDERS: dict[str, dict[str, Any]] = {
    "PO-10001": {
        "id": "PO-10001",
        "supplier": "ABC Technology",
        "amount": 12800,
        "currency": "CNY",
        "status": "Delivered",
    },
    "PO-10002": {
        "id": "PO-10002",
        "supplier": "Nova Office",
        "amount": 7600,
        "currency": "CNY",
        "status": "Delivered",
    },
    "PO-10003": {
        "id": "PO-10003",
        "supplier": "Cloud Harbor",
        "amount": 7600,
        "currency": "CNY",
        "status": "Delivered",
    },
    # Java 版新增：演示"付款后进程崩溃"用
    "PO-10004": {
        "id": "PO-10004",
        "supplier": "Lumen Supplies",
        "amount": 5200,
        "currency": "CNY",
        "status": "Delivered",
    },
}

INVOICES: dict[str, dict[str, Any]] = {
    "PO-10001": {
        "invoice_id": "INV-8921",
        "po_id": "PO-10001",
        "supplier": "ABC Technology",
        "amount": 12800,
        "status": "Valid",
    },
    "PO-10002": {
        "invoice_id": "INV-8922",
        "po_id": "PO-10002",
        "supplier": "Nova Office",
        "amount": 7600,
        "status": "Valid",
    },
    "PO-10003": {
        "invoice_id": "INV-8923",
        "po_id": "PO-10003",
        "supplier": "Cloud Harbor",
        "amount": 7500,
        "status": "Valid",
    },
    "PO-10004": {
        "invoice_id": "INV-8924",
        "po_id": "PO-10004",
        "supplier": "Lumen Supplies",
        "amount": 5200,
        "status": "Valid",
    },
}
