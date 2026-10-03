# Replace with keel/python-runtime when that base image is published.
FROM python:3.11-slim
WORKDIR /app
COPY . /app
RUN pip install --no-cache-dir keel-sdk uvicorn pytest httpx
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
