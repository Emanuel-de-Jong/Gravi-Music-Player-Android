import json
from collections import defaultdict
from math import ceil
from pathlib import Path
from statistics import median
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

    measurements = data.get("measurements")
    if not isinstance(measurements, list):
        raise SystemExit("Invalid JSON: missing measurements list")

    durations_by_label: dict[str, list[float]] = defaultdict(list)
    for measurement in measurements:
        if not isinstance(measurement, dict):
            continue

        label = measurement.get("label")
        duration_ms = measurement.get("durationMs")
        if isinstance(label, str) and isinstance(duration_ms, int | float):
            durations_by_label[label].append(float(duration_ms))

    entries = []
    for label, durations_ms in durations_by_label.items():
        filtered_durations_ms = filter_outliers(durations_ms)
        average_duration_ms = sum(filtered_durations_ms) / len(filtered_durations_ms)
        entries.append(
            {
                "label": label,
                "count": len(durations_ms),
                "keptCount": len(filtered_durations_ms),
                "averageDurationMs": average_duration_ms,
                "minDurationMs": min(filtered_durations_ms),
                "maxDurationMs": max(filtered_durations_ms),
            }
        )

    rows = []
    for entry in sorted(entries, key=lambda entry: entry["averageDurationMs"]):
        label = entry["label"]
        kept_count = entry["keptCount"]
        average_duration_ms = entry["averageDurationMs"]
        duration_seconds = average_duration_ms / 1000
        filtered_percentage = (entry["count"] - kept_count) / entry["count"] * 100
        min_duration_ms = entry["minDurationMs"]
        max_duration_ms = entry["maxDurationMs"]

        rows.append(
            [
                f"{label} count: {kept_count}",
                f"avg: {average_duration_ms:.0f}ms ({duration_seconds:.2f}s)",
                f"range: {min_duration_ms:.0f}ms - {max_duration_ms:.0f}ms",
                f"filtered: {filtered_percentage:.1f}%",
            ]
        )

    column_widths = [
        max(len(row[index]) for row in rows) for index in range(len(rows[0]))
    ]
    for row in rows:
        print(
            " | ".join(
                column.ljust(column_widths[index]) for index, column in enumerate(row)
            )
        )


def filter_outliers(durations_ms: list[float]) -> list[float]:
    if len(durations_ms) >= 25:
        return trim_percentiles(durations_ms, 0.01)

    return trim_small_sample_outliers(durations_ms)


def trim_percentiles(durations_ms: list[float], trim_ratio: float) -> list[float]:
    sorted_durations_ms = sorted(durations_ms)
    trim_count = max(1, ceil(len(sorted_durations_ms) * trim_ratio))

    trimmed_durations_ms = sorted_durations_ms[trim_count:-trim_count]
    return trimmed_durations_ms or sorted_durations_ms


def trim_small_sample_outliers(durations_ms: list[float]) -> list[float]:
    if len(durations_ms) < 4:
        return list(durations_ms)

    sorted_durations_ms = sorted(durations_ms)
    gaps = [
        sorted_durations_ms[index + 1] - sorted_durations_ms[index]
        for index in range(len(sorted_durations_ms) - 1)
    ]
    positive_gaps = [gap for gap in gaps if gap > 0]
    if len(positive_gaps) < 2:
        return sorted_durations_ms

    typical_gap = median(positive_gaps)
    if typical_gap <= 0:
        return sorted_durations_ms

    unusual_gap_indexes = [
        index for index, gap in enumerate(gaps) if gap >= typical_gap * 10
    ]
    if not unusual_gap_indexes:
        return sorted_durations_ms

    best_filtered_durations_ms = sorted_durations_ms
    best_score = 0.0

    for gap_index in unusual_gap_indexes:
        low_cluster_end = gap_index + 1
        high_cluster_start = gap_index + 1
        low_cluster = sorted_durations_ms[:low_cluster_end]
        high_cluster = sorted_durations_ms[high_cluster_start:]

        if should_remove_edge_cluster(low_cluster, sorted_durations_ms):
            filtered_durations_ms = sorted_durations_ms[low_cluster_end:]
            score = gaps[gap_index] / typical_gap - len(low_cluster)
            if score > best_score:
                best_filtered_durations_ms = filtered_durations_ms
                best_score = score

        if should_remove_edge_cluster(high_cluster, sorted_durations_ms):
            filtered_durations_ms = sorted_durations_ms[:high_cluster_start]
            score = gaps[gap_index] / typical_gap - len(high_cluster)
            if score > best_score:
                best_filtered_durations_ms = filtered_durations_ms
                best_score = score

    return best_filtered_durations_ms or sorted_durations_ms


def should_remove_edge_cluster(cluster: list[float], values: list[float]) -> bool:
    if not cluster:
        return False

    if len(cluster) >= len(values) / 2:
        return False

    return len(cluster) <= 2


if __name__ == "__main__":
    main()
