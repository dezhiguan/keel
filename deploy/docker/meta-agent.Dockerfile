# The image is built from agents/meta-agent/Dockerfile.
# The context is that directory, not the platform source tree.
FROM python:3.11-slim
WORKDIR /app
COPY agents/meta-agent/vendor/keel_sdk-*.whl /tmp/keel-sdk/
RUN pip install --no-cache-dir /tmp/keel-sdk/keel_sdk-*.whl && rm -rf /tmp/keel-sdk
COPY agents/meta-agent/app.py agents/meta-agent/workflow.py agents/meta-agent/candidate.py agents/meta-agent/evalset.py agents/meta-agent/roles.py agents/meta-agent/agent.yaml /app/
COPY agents/meta-agent/tools /app/tools
COPY agents/meta-agent/prompts /app/prompts
COPY agents/meta-agent/evals /app/evals
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
