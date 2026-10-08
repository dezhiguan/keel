# Local sandbox image. It does not install a vendor SDK.
FROM python:3.11-slim
WORKDIR /app
COPY fake_llm.py fake_langfuse.py runner.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 HOME=/tmp TMPDIR=/tmp
USER 65534
CMD ["python", "/app/runner.py"]
