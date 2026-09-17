"""HTTP runner entry scaffold. Never substitutes a Python retrieval algorithm."""

import argparse


def main() -> None:
    parser = argparse.ArgumentParser(
        description="MindPet-Eval runner scaffold: Java evaluation API is not implemented."
    )
    parser.add_argument("--config", default="configs/retrieval.yaml")
    parser.parse_args()
    parser.exit(
        2,
        "NOT_IMPLEMENTED: Java API and isolated dataset are not ready; "
        "no HTTP requests or results were produced.\n",
    )


if __name__ == "__main__":
    main()
