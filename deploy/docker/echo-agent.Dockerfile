FROM python:3.11-slim
WORKDIR /src
COPY contracts /src/contracts
COPY templates /src/templates
COPY sdk-python /src/sdk-python
RUN pip install --no-cache-dir '/src/sdk-python[eval]' \
 && python -c "from importlib.resources import files; assert files('keel').joinpath('_schemas','manifest.schema.json').is_file()"
WORKDIR /app
COPY agents/echo /app
RUN test -s /app/prompts/answer.md \
 && python -c "import app; print('echo agent import ok')"
ENV KEEL_TRACE_BUFFER_PATH=/tmp/keel-traces
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
