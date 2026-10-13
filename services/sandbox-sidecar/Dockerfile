# Build from repository root. Runner has the Keel SDK and test tools but no credentials.
FROM python:3.11-slim
COPY contracts /src/contracts
COPY sdk-python /src/sdk-python
RUN pip install --no-cache-dir /src/sdk-python pytest
WORKDIR /app
COPY services/sandbox-sidecar/fake_llm.py services/sandbox-sidecar/fake_langfuse.py services/sandbox-sidecar/runner.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 HOME=/tmp TMPDIR=/tmp
USER 65534
CMD ["python", "/app/runner.py"]
