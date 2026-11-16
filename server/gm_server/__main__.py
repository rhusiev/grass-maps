"""Entry point: ``python -m gm_server``."""

from __future__ import annotations

import logging
import signal
import sys
import threading

from gm_server.app import create_server
from gm_server.config import Config, ConfigError


def main() -> int:
    """Runs the server until SIGTERM or SIGINT. Returns the exit code."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    log = logging.getLogger("gm_server")
    try:
        config = Config.from_env()
    except ConfigError as exc:
        log.error("configuration error: %s", exc)
        return 2
    server = create_server(config)
    service = server.service  # type: ignore[attr-defined]
    if config.require_tokens and service.tokens.count_enabled() == 0:
        log.warning("tokens are required but %s holds none: every request gets 401", config.tokens_file)
    if not config.require_tokens:
        log.warning("running WITHOUT tokens: limits apply per %s", config.anon_key)
    stop = threading.Event()

    def request_stop(_signal: int, _frame: object) -> None:
        stop.set()

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    log.info("serving %s on %s:%d", config.packs_dir, config.host, server.server_address[1])
    stop.wait()
    server.shutdown()
    service.usage.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
