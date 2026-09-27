"""Delete only the fixed E2E user from the dedicated E2E database."""

from __future__ import annotations

import argparse
import json

from e2e_db import DatabaseConfig, reset_eval_user, snapshot


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-user", required=True, choices=["e2e_memory_eval_user"])
    args = parser.parse_args()
    del args
    with DatabaseConfig.from_env().connect() as connection:
        deleted = reset_eval_user(connection)
        after = snapshot(connection)
    print(json.dumps({"status": "OK", "deleted": deleted, "after": after}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
