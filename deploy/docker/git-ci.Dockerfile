FROM python:3.11-slim
WORKDIR /src
COPY contracts /src/contracts
COPY sdk-python /src/sdk-python
RUN pip install --no-cache-dir /src/sdk-python
COPY services/git-ci/git_ci /app/git_ci
WORKDIR /app
EXPOSE 8000
CMD ["uvicorn", "git_ci.server:app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
