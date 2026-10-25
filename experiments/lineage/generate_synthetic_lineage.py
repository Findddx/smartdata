#!/usr/bin/env python3
"""Run only on the explicitly authorized experiment host. Never reads customer data."""
import argparse
import csv
import hashlib
import json
import os
import platform
import random
import resource
import sqlite3
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--output", type=Path, required=True)
    args.add_argument("--orders", type=int, default=250000)
    parsed = args.parse_args()
    if not 1000 <= parsed.orders <= 500000:
        raise ValueError("bounded synthetic experiment: 1000..500000 orders")
    os.umask(0o077)
    root = parsed.output.resolve()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    (root / "inputs").mkdir(); (root / "outputs").mkdir(); (root / "manifests").mkdir()
    seed = 20261025
    rng = random.Random(seed)
    started = time.monotonic()
    customer_count = 10000
    customer_file = root / "inputs/customers.csv"
    order_file = root / "inputs/orders.csv"
    with customer_file.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle); writer.writerow(["customer_id", "region"])
        writer.writerows((f"C{i:06d}", ["north", "south", "east", "west"][i % 4]) for i in range(customer_count))
    with order_file.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["order_id", "customer_id", "order_day", "paid_cents", "refund_cents", "late"])
        for i in range(parsed.orders):
            paid = rng.randrange(100, 200001)
            refund = rng.randrange(paid + 1) if rng.randrange(12) == 0 else 0
            writer.writerow((f"O{i:09d}", f"C{rng.randrange(customer_count):06d}", f"2026-09-{rng.randrange(1, 29):02d}", paid, refund, int(rng.randrange(7) == 0)))
    db_path = root / "synthetic.sqlite"
    db = sqlite3.connect(db_path)
    db.execute("pragma journal_mode=WAL"); db.execute("pragma synchronous=FULL")
    db.execute("pragma cache_size=-65536"); db.execute("pragma threads=1")
    db.executescript("create table raw_customers(customer_id text primary key,region text not null); create table raw_orders(order_id text primary key,customer_id text not null references raw_customers(customer_id),order_day text not null,paid_cents integer not null,refund_cents integer not null,late integer not null);")
    for path, table, types in [(customer_file, "raw_customers", [str, str]), (order_file, "raw_orders", [str, str, str, int, int, int])]:
        with path.open(newline="", encoding="utf-8") as handle:
            reader = csv.reader(handle); next(reader)
            db.executemany(f"insert into {table} values({','.join('?' for _ in types)})", (tuple(convert(value) for convert, value in zip(types, row)) for row in reader))
    transforms = [
        ("customer_entities", ["raw_customers", "raw_orders"], """create table customer_entities as select c.customer_id,c.region,count(o.order_id) as order_count,coalesce(sum(o.paid_cents-o.refund_cents),0) as net_cents from raw_customers c left join raw_orders o on c.customer_id=o.customer_id group by c.customer_id,c.region"""),
        ("daily_metrics", ["raw_orders"], """create table daily_metrics as select order_day,count(*) as orders,count(distinct customer_id) as customers,sum(paid_cents) as paid_cents,sum(refund_cents) as refund_cents,sum(paid_cents-refund_cents) as net_cents,sum(late) as late_orders from raw_orders group by order_day"""),
        ("decision_candidates", ["daily_metrics"], """create table decision_candidates as select order_day,late_orders,orders,'INVESTIGATE_FULFILLMENT' as proposed_action from daily_metrics where late_orders*100>orders*10"""),
    ]
    run_id = "synthetic-lineage-seed-20261025"
    events = []
    for output, inputs, sql in transforms:
        db.execute(sql)
        events.append({"eventType": "COMPLETE", "run": {"runId": run_id + "-" + output}, "job": {"namespace": "smartdata.synthetic", "name": output},
            "inputs": [{"namespace": "smartdata.synthetic", "name": item} for item in inputs], "outputs": [{"namespace": "smartdata.synthetic", "name": output}],
            "producer": "smartdata-synthetic-lineage-v1", "sqlSha256": hashlib.sha256(sql.encode()).hexdigest(), "provenance": "DEMO"})
    db.commit()
    output_summary = {}
    for table, _, _ in transforms:
        cursor = db.execute(f"select * from {table} order by 1")
        path = root / "outputs" / (table + ".csv")
        count = 0
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle); writer.writerow([col[0] for col in cursor.description])
            for row in cursor:
                writer.writerow(row); count += 1
        output_summary[table] = {"rows": count, "sha256": sha(path), "path": "outputs/" + path.name,
            "materialization": "MATERIALIZED_DEMO" if table != "decision_candidates" else "COMPUTED_PLAN_ONLY", "provenance": "DEMO"}
    # Independent implementation: stream CSV with Python integers; no SQL/grouping code reused.
    independent_net = 0; independent_late = 0; order_count = 0; expected_per_customer = {}
    with order_file.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            net = int(row["paid_cents"]) - int(row["refund_cents"])
            independent_net += net; independent_late += int(row["late"]); order_count += 1
            count, amount = expected_per_customer.get(row["customer_id"], (0, 0))
            expected_per_customer[row["customer_id"]] = (count + 1, amount + net)
    actual_net, actual_late, actual_orders = db.execute("select sum(net_cents),sum(late_orders),sum(orders) from daily_metrics").fetchone()
    assert (actual_net, actual_late, actual_orders) == (independent_net, independent_late, order_count)
    for customer, count, net in db.execute("select customer_id,order_count,net_cents from customer_entities"):
        assert (count, net) == expected_per_customer.get(customer, (0, 0))
    assert db.execute("pragma integrity_check").fetchone()[0] == "ok"
    assert db.execute("select count(*) from raw_orders where refund_cents>paid_cents").fetchone()[0] == 0
    assert db.execute("select count(*) from raw_orders o left join raw_customers c on o.customer_id=c.customer_id where c.customer_id is null").fetchone()[0] == 0
    plan_rows = db.execute("select count(*) from decision_candidates").fetchone()[0]
    db.execute("pragma wal_checkpoint(TRUNCATE)"); db.close()
    gpu = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"], capture_output=True, text=True, check=True).stdout.strip()
    manifests = root / "manifests"
    write_json(manifests / "input.json", {"provenance": "DEMO", "seed": seed, "files": [{"path": str(p.relative_to(root)), "sha256": sha(p)} for p in [customer_file, order_file]], "orders": order_count, "customers": customer_count, "realCustomerDataUsed": False})
    write_json(manifests / "method.json", {"version": "SYNTHETIC_RELATIONAL_LINEAGE_V1", "scriptSha256": sha(Path(__file__)), "transforms": [{"output": name, "inputs": inputs, "sql": sql} for name, inputs, sql in transforms], "environment": {"python": platform.python_version(), "sqlite": sqlite3.sqlite_version, "os": platform.system(), "machine": platform.machine(), "gpuHardware": gpu, "computationDevice": "CPU_ON_AUTHORIZED_GPU_HOST", "gpuAccelerationUsed": False, "cpuAffinity": sorted(os.sched_getaffinity(0))}})
    write_json(manifests / "assumptions.json", {"currency": "CNY", "moneyUnit": "integer_cent", "timeZone": "UTC", "netRevenueDefinition": "sum(paid_cents-refund_cents)", "lateMeaning": "synthetic binary observation", "entityIdentity": "exact generated customer_id join; no fuzzy identity or inferred real customer", "decisionThreshold": "late_orders/orders > 10%; synthetic assumption, not a validated business policy"})
    write_json(manifests / "output.json", {"datasets": output_summary, "aggregateCheck": {"orders": order_count, "netCents": independent_net, "lateOrders": independent_late, "decisionPlans": plan_rows}, "databaseSha256": sha(db_path), "elapsedSeconds": round(time.monotonic() - started, 3), "maxRssKiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss})
    write_json(manifests / "independent-validation.json", {"status": "PASSED", "method": "independent streaming CSV Python integer aggregation versus SQLite materialized SQL", "assertions": ["every customer order count and net amount equals independent input aggregation", "daily total count/net/late equals independent inputs", "SQLite integrity_check ok", "no orphan customers", "refund does not exceed paid"], "rawInputsReadOnlyDuringValidation": True})
    write_json(manifests / "limitations.json", {"provenance": "DEMO", "notClaims": ["not customer data", "not real ROI", "not GPU acceleration", "not enterprise load capacity", "no source-system write", "no automatic customer extraction", "decision plans are not executed decisions"], "applicationImport": "NOT_IMPORTED; experiment artifacts are isolated from USER workspace sources", "fieldTransformCoverage": "only SQL explicitly executed by this script is known; no external system field transformations inferred"})
    write_json(manifests / "execution-lineage.json", {"provenance": "DEMO", "events": events})
    write_json(root / "manifest-index.json", {"version": 1, "createdAt": datetime.now(timezone.utc).isoformat(), "manifests": {p.name: sha(p) for p in sorted(manifests.glob("*.json"))}})
    print(json.dumps({"status": "PASSED", "outputDirectory": str(root), "orders": order_count, "customers": customer_count, "manifestIndexSha256": sha(root / "manifest-index.json"), "device": "CPU_ON_GPU_HOST"}, sort_keys=True))


if __name__ == "__main__":
    main()
