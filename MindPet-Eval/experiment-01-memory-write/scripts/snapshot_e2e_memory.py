"""Create a deterministic five-table snapshot of the dedicated E2E database."""

from __future__ import annotations

import argparse
from pathlib import Path

from e2e_db import DatabaseConfig, snapshot, write_json


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with DatabaseConfig.from_env().connect() as connection:
        result = snapshot(connection)
    write_json(args.output, result)
    print(f"snapshot written: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
