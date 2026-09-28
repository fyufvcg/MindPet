"""Metric aggregation entry scaffold. Produces no placeholder metrics."""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(
        description="MindPet-Eval aggregation scaffold: requires real validated run output."
    )
    parser.add_argument("--config", default="configs/retrieval.yaml")
    parser.parse_args()
    parser.exit(
        2,
        "NOT_IMPLEMENTED: metrics and validated raw results are pending; "
        "no CSV or figures were written.\n",
    )


if __name__ == "__main__":
    main()
