#!/usr/bin/env python3
"""Separate read-only CSV verifier. Does not execute the pipeline SQL or read its database."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1048576), b""):
            value.update(block)
    return value.hexdigest()


def rows(path):
    with path.open(newline="", encoding="utf8") as handle:
        yield from csv.DictReader(handle)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    options = parser.parse_args()
    root = options.run.resolve()
    index = json.loads((root / "manifest-index.json").read_text())
    for name, expected in index["manifests"].items():
        assert Path(name).name == name
        assert sha(root / "manifests" / name) == expected
    inputs = json.loads((root / "manifests/input.json").read_text())
    outputs = json.loads((root / "manifests/output.json").read_text())
    for entry in inputs["files"]:
        path = root / entry["path"]
        assert path.resolve().is_relative_to(root)
        assert sha(path) == entry["sha256"]
    for entry in outputs["datasets"].values():
        path = root / entry["path"]
        assert path.resolve().is_relative_to(root)
        assert sha(path) == entry["sha256"]
    assert sha(root / "synthetic.sqlite") == outputs["databaseSha256"]
    customers = {row["customer_id"]: row["region"] for row in rows(root / "inputs/customers.csv")}
    per_customer = {customer: [0, 0] for customer in customers}
    per_day = {}
    for row in rows(root / "inputs/orders.csv"):
        paid, refund, late = (int(row[name]) for name in ["paid_cents", "refund_cents", "late"])
        assert row["customer_id"] in customers and 0 <= refund <= paid and late in [0, 1]
        per_customer[row["customer_id"]][0] += 1
        per_customer[row["customer_id"]][1] += paid - refund
        day = per_day.setdefault(row["order_day"], {"orders": 0, "customers": set(), "paid_cents": 0, "refund_cents": 0, "net_cents": 0, "late_orders": 0})
        day["orders"] += 1; day["customers"].add(row["customer_id"])
        day["paid_cents"] += paid; day["refund_cents"] += refund; day["net_cents"] += paid - refund; day["late_orders"] += late
    entity_rows = list(rows(root / "outputs/customer_entities.csv"))
    assert len(entity_rows) == len(per_customer)
    assert len({row["customer_id"] for row in entity_rows}) == len(per_customer)
    for row in entity_rows:
        assert customers[row["customer_id"]] == row["region"]
        assert [int(row["order_count"]), int(row["net_cents"])] == per_customer[row["customer_id"]]
    actual_days = list(rows(root / "outputs/daily_metrics.csv"))
    assert {row["order_day"] for row in actual_days} == set(per_day)
    for row in actual_days:
        for key, value in per_day[row["order_day"]].items():
            assert int(row[key]) == (len(value) if isinstance(value, set) else value)
    expected_plans = {day: value for day, value in per_day.items() if value["late_orders"] * 100 > value["orders"] * 10}
    plans = list(rows(root / "outputs/decision_candidates.csv"))
    assert {row["order_day"] for row in plans} == set(expected_plans)
    for row in plans:
        expected = expected_plans[row["order_day"]]
        assert row["proposed_action"] == "INVESTIGATE_FULFILLMENT"
        assert int(row["orders"]) == expected["orders"] and int(row["late_orders"]) == expected["late_orders"]
    print(json.dumps({"status": "PASSED", "method": "SEPARATE_READ_ONLY_CSV_RECOMPUTATION_V1", "validatorSha256": sha(Path(__file__)),
        "manifestIndexSha256": sha(root / "manifest-index.json"), "customerEntitiesChecked": len(entity_rows), "dailyMetricsChecked": len(actual_days),
        "decisionPlansChecked": len(plans), "usesPipelineSQL": False, "originalInputsModified": False, "realBusinessOutcomeClaimed": False}, sort_keys=True))


if __name__ == "__main__":
    main()
