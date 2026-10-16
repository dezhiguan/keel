FROM python:3.11-slim
WORKDIR /app
COPY vendor/keel_sdk-*.whl /tmp/keel-sdk/
RUN wheel="$(find /tmp/keel-sdk -name 'keel_sdk-*.whl' -print -quit)" \
 && pip install --no-cache-dir "${wheel}" 'langfuse==4.17.0' \
 && rm -rf /tmp/keel-sdk
COPY app.py agent.yaml /app/
COPY prompts /app/prompts
COPY evals /app/evals
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
