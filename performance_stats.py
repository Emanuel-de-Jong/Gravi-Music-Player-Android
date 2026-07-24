import json
from pathlib import Path
from typing import Any

def main() -> None:
    json_path = Path(__file__).with_name("gravi-performance-data.json")
    try:
        with json_path.open("r", encoding="utf-8") as file:
            data: dict[str, Any] = json.load(file)
    except FileNotFoundError:
        raise SystemExit(f"File not found: {json_path}")
    except json.JSONDecodeError as error:
        raise SystemExit(f"Invalid JSON: {error}")

    summary = data.get("summary")

    valid_entries = [entry for entry in summary]
    valid_entries.sort(key=lambda entry: entry["averageDurationMs"])
    for entry in valid_entries:
        label = entry["label"]
        count = entry["count"]
        average_duration_ms = entry["averageDurationMs"]
        duration_seconds = average_duration_ms / 1000

        print(
            f"{label}: {count} times | {average_duration_ms:g}ms ({duration_seconds:.1f}s)"
        )

if __name__ == "__main__":
    main()
