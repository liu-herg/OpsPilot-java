from __future__ import annotations

from decimal import Decimal
from typing import Any
from uuid import uuid4

from sqlalchemy import JSON, Integer, Numeric, String, UniqueConstraint, delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import (
    AsyncAttrs,
    AsyncEngine,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(AsyncAttrs, DeclarativeBase):
    pass


class PaymentRequestRecord(Base):
    __tablename__ = "finance_payment_requests"
    __table_args__ = (UniqueConstraint("po_number", "invoice_id"),)

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    business_key: Mapped[str] = mapped_column(String(200), unique=True, index=True)
    po_number: Mapped[str] = mapped_column(String(80), index=True)
    invoice_id: Mapped[str] = mapped_column(String(80), index=True)
    supplier: Mapped[str] = mapped_column(String(200))
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    cost_center: Mapped[str] = mapped_column(String(120))
    reason: Mapped[str] = mapped_column(String(1000))
    status: Mapped[str] = mapped_column(String(40), default="Submitted")
    extra: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)


class PaymentStore:
    def __init__(self, database_url: str) -> None:
        self.engine: AsyncEngine = create_async_engine(database_url)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        self._initialized = True

    async def close(self) -> None:
        await self.engine.dispose()
        self._initialized = False

    @staticmethod
    def business_key(po_number: str, invoice_id: str) -> str:
        return f"payment:{po_number}:{invoice_id}"

    @staticmethod
    def _public(record: PaymentRequestRecord) -> dict[str, Any]:
        return {
            "id": record.id,
            "status": record.status,
            "po_number": record.po_number,
            "invoice_id": record.invoice_id,
            "supplier": record.supplier,
            "amount": record.amount,
            "cost_center": record.cost_center,
            "reason": record.reason,
            **record.extra,
        }

    async def create_or_get(self, payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        await self.initialize()
        key = self.business_key(payload["po_number"], payload["invoice_id"])
        record = PaymentRequestRecord(
            id=f"PAY-{uuid4().hex[:8].upper()}",
            business_key=key,
            status="Submitted",
            **payload,
        )
        async with self.sessions() as session:
            session.add(record)
            try:
                await session.commit()
                return self._public(record), True
            except IntegrityError:
                await session.rollback()
                existing = await session.scalar(
                    select(PaymentRequestRecord).where(PaymentRequestRecord.business_key == key)
                )
                if existing is None:
                    raise
                return self._public(existing), False

    async def get(self, po_number: str, invoice_id: str) -> dict[str, Any] | None:
        await self.initialize()
        key = self.business_key(po_number, invoice_id)
        async with self.sessions() as session:
            record = await session.scalar(
                select(PaymentRequestRecord).where(PaymentRequestRecord.business_key == key)
            )
            return self._public(record) if record is not None else None

    async def snapshot(self) -> dict[str, dict[str, Any]]:
        await self.initialize()
        async with self.sessions() as session:
            records = (await session.scalars(select(PaymentRequestRecord))).all()
            return {record.business_key: self._public(record) for record in records}

    async def restore(self, payments: dict[str, dict[str, Any]]) -> None:
        await self.initialize()
        records: list[PaymentRequestRecord] = []
        for value in payments.values():
            data = dict(value)
            record_id = str(data.pop("id", f"PAY-{uuid4().hex[:8].upper()}"))
            status = str(data.pop("status", "Submitted"))
            po_number = str(data["po_number"])
            invoice_id = str(data["invoice_id"])
            known = {
                name: data.pop(name)
                for name in (
                    "po_number",
                    "invoice_id",
                    "supplier",
                    "amount",
                    "cost_center",
                    "reason",
                )
            }
            records.append(
                PaymentRequestRecord(
                    id=record_id,
                    business_key=self.business_key(po_number, invoice_id),
                    status=status,
                    extra=data,
                    **known,
                )
            )
        async with self.sessions() as session:
            await session.execute(delete(PaymentRequestRecord))
            session.add_all(records)
            await session.commit()

    async def reset(self) -> None:
        await self.initialize()
        async with self.sessions() as session:
            await session.execute(delete(PaymentRequestRecord))
            await session.commit()
