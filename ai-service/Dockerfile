FROM python:3.12-slim-bookworm AS builder
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /build
COPY ai-service/requirements.txt ./requirements.txt
RUN apt-get update \
    && apt-get install --no-install-recommends -y build-essential \
    && pip wheel --no-cache-dir --wheel-dir /wheels -r requirements.txt \
    && rm -rf /var/lib/apt/lists/*

FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY ai-service/requirements.txt ./requirements.txt
COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links=/wheels -r requirements.txt \
    && groupadd --gid 10001 nla \
    && useradd --uid 10001 --gid nla --no-create-home nla \
    && rm -rf /wheels
COPY --chown=10001:10001 ai-service/*.py ./
COPY --chown=10001:10001 ai-service/wren/ ./wren/
COPY --chown=10001:10001 ai-service/third_party/wren_semantic/ ./third_party/wren_semantic/
COPY --chown=10001:10001 ai-service/third-party/licenses/ /app/third-party/licenses/
COPY --chown=10001:10001 infra/docker/ai-health.py /app/ai-health.py
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
