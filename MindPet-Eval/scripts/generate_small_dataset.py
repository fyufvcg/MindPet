"""Dataset entry scaffold. No data is generated before design approval."""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(
        description="MindPet-Eval dataset scaffold: planned 120 memories/40 queries."
    )
    parser.add_argument("--config", default="configs/retrieval.yaml")
    parser.parse_args()
    parser.exit(
        2,
        "NOT_IMPLEMENTED: dataset generation awaits review; no files were written.\n",
    )


if __name__ == "__main__":
    main()
