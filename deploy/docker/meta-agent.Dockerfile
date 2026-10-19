# The image is built from agents/meta-agent/Dockerfile.
# The context is that directory, not the platform source tree.
FROM python:3.11-slim
WORKDIR /app
COPY agents/meta-agent/vendor/keel_sdk-*.whl /tmp/keel-sdk/
RUN pip install --no-cache-dir /tmp/keel-sdk/keel_sdk-*.whl && rm -rf /tmp/keel-sdk
COPY agents/meta-agent/*.py agents/meta-agent/agent.yaml /app/
COPY agents/meta-agent/pre-review-workflow.yml /app/
COPY agents/meta-agent/tools /app/tools
COPY agents/meta-agent/prompts /app/prompts
COPY agents/meta-agent/evals /app/evals
COPY agents/meta-agent/skills /app/skills
COPY agents/meta-agent/ops /app/ops
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
