FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir pymysql pyyaml starlette uvicorn

COPY pyproject.toml config.yaml ./
COPY src/ ./src/

EXPOSE 8081

CMD ["python", "-m", "mcp_server.src.server"]
