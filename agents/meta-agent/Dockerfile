# Image context is this directory. The SDK wheel is staged in vendor/ by CI
# and is not committed. Scaffold templates ship inside the wheel.
FROM python:3.11-slim
WORKDIR /app
COPY vendor/keel_sdk-*.whl /tmp/keel-sdk/
RUN wheel="$(find /tmp/keel-sdk -name 'keel_sdk-*.whl' -print -quit)" \
 && pip install --no-cache-dir "${wheel}" 'langfuse==4.17.0' \
 && python -c "import langfuse; from pathlib import Path; import keel.cli.new as new; path = Path(new.__file__).resolve().parents[1] / '_templates' / 'loop-agent' / 'agent.yaml'; assert path.is_file(), path" \
 && rm -rf /tmp/keel-sdk
COPY *.py agent.yaml /app/
COPY pre-review-workflow.yml /app/
COPY tools /app/tools
COPY prompts /app/prompts
COPY evals /app/evals
COPY skills /app/skills
COPY ops /app/ops
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
