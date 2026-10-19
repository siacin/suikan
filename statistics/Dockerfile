FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home suikan && mkdir /data && chown suikan:suikan /data
COPY server.py dashboard.html dashboard.js dashboard.css favicon.svg ./
USER suikan
ENV SUIKAN_DB=/data/usage.sqlite3
ENV PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "4", "--timeout", "30", "--error-logfile", "-", "server:application"]
