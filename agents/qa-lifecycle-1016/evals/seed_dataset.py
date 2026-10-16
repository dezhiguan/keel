"""Add missing acceptance cases to the existing Langfuse dataset, without duplicates."""
import json
from pathlib import Path

from langfuse import Langfuse


DATASET = "qa-lifecycle-1016/smoke"


def main():
    client = Langfuse()
    try:
        existing = {item.input.get("text") for item in client.get_dataset(DATASET).items
                    if isinstance(item.input, dict)}
        added = 0
        for line in (Path(__file__).parent / "seed.jsonl").read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            if row["input"] in existing:
                continue
            client.create_dataset_item(dataset_name=DATASET, input={"text": row["input"]},
                                       expected_output=row["expected"], metadata={"tags": row["tags"]})
            existing.add(row["input"])
            added += 1
        print(f"dataset={DATASET} added={added} total={len(existing)}")
    finally:
        client.flush()
        client.shutdown()


if __name__ == "__main__":
    main()
