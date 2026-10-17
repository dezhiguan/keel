# Image context is this directory. The SDK wheel is staged in vendor/ by CI
# and is not committed. Scaffold templates ship inside the wheel.
FROM python:3.11-slim
WORKDIR /app
COPY vendor/keel_sdk-*.whl /tmp/keel-sdk/
RUN pip install --no-cache-dir /tmp/keel-sdk/keel_sdk-*.whl \
 && python -c "from pathlib import Path; import keel.cli.new as new; path = Path(new.__file__).resolve().parents[1] / '_templates' / 'loop-agent' / 'agent.yaml'; assert path.is_file(), path" \
 && rm -rf /tmp/keel-sdk
COPY app.py workflow.py candidate.py evalset.py roles.py agent.yaml platform_contract_test.py /app/
COPY pre-review-workflow.yml /app/
COPY tools /app/tools
COPY prompts /app/prompts
COPY evals /app/evals
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
