FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY ai-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt \
    && groupadd --gid 10001 nla \
    && useradd --uid 10001 --gid nla --no-create-home nla
COPY --chown=10001:10001 ai-service/*.py ./
COPY --chown=10001:10001 ai-service/third-party/licenses/ /app/third-party/licenses/
COPY --chown=10001:10001 infra/docker/ai-health.py /app/ai-health.py
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
