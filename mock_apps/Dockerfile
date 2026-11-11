# 三个模拟业务系统（采购 / 发票 / 财务），沿用 Python 版 OpsPilot 的代码，三个服务共用这一个镜像。
FROM python:3.12-slim
WORKDIR /app
# 国内网络默认用阿里云镜像；想用官方源：docker compose build --build-arg PIP_INDEX_URL=https://pypi.org/simple
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
# sqlalchemy 2.1 起异步模块需要 greenlet，要用 sqlalchemy[asyncio] 才会装上
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} aiosqlite fastapi pydantic "sqlalchemy[asyncio]" uvicorn
COPY mock_apps ./mock_apps
ENV PYTHONPATH=/app
