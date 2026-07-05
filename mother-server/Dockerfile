FROM python:3.11-slim
WORKDIR /app
RUN pip install --no-cache-dir fastapi uvicorn httpx python-dotenv websockets qrcode
COPY . .
RUN mkdir -p /var/lib/mbclaw
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD curl -f http://localhost:8000/health || exit 1
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
