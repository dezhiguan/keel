FROM python:3.11-slim
WORKDIR /src
COPY contracts /src/contracts
COPY sdk-python /src/sdk-python
COPY templates /src/templates
RUN pip install --no-cache-dir /src/sdk-python
WORKDIR /app
COPY agents/meta-agent /app
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
