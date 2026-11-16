FROM docker.io/library/python:3.13-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY gm_server ./gm_server
RUN printf '#!/bin/sh\nexec python3 -m gm_server.token_cli "$@"\n' > /usr/local/bin/gm-token \
    && chmod 755 /usr/local/bin/gm-token \
    && mkdir -p /packs /config /state \
    && chown 10001:10001 /config /state

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD python3 -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8080/healthz', timeout=2)"
CMD ["python3", "-m", "gm_server"]
