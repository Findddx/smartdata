#!/usr/bin/env python3
"""Audit frozen SQL input declarations using SQLite's compile-time read callbacks."""
import argparse
import hashlib
import json
import re
import sqlite3
from pathlib import Path


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1048576), b""):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    root = parser.parse_args().run.resolve()
    index = json.loads((root / "manifest-index.json").read_text())
    method_path = root / "manifests/method.json"
    assert sha(method_path) == index["manifests"]["method.json"]
    method = json.loads(method_path.read_text())
    database = root / "synthetic.sqlite"
    before = sha(database)
    expected_output = json.loads((root / "manifests/output.json").read_text())
    assert sha(root / "manifests/output.json") == index["manifests"]["output.json"]
    assert before == expected_output["databaseSha256"]
    connection = sqlite3.connect(database.as_uri() + "?mode=ro&immutable=1", uri=True)
    reports = []
    for transform in method["transforms"]:
        assert re.fullmatch(r"[a-z_]+", transform["output"])
        prefix = "create table " + transform["output"] + " as "
        assert transform["sql"].startswith(prefix)
        select = transform["sql"][len(prefix):]
        assert select.lstrip().lower().startswith("select ")
        reads = {}
        def authorize(action, table, column, namespace, origin):
            if action == sqlite3.SQLITE_READ:
                reads.setdefault(table, set()).add(column or "[ROW_COUNT_ONLY]")
                return sqlite3.SQLITE_OK
            return sqlite3.SQLITE_OK if action in [sqlite3.SQLITE_SELECT, sqlite3.SQLITE_FUNCTION] else sqlite3.SQLITE_DENY
        connection.set_authorizer(authorize)
        connection.execute("EXPLAIN QUERY PLAN " + select).fetchall()
        assert set(reads) == set(transform["inputs"]), (transform["output"], sorted(reads))
        reports.append({"output": transform["output"], "declaredInputsMatchEngine": True,
            "observedReadColumns": {table: sorted(columns) for table, columns in sorted(reads.items())}})
    connection.close()
    assert sha(database) == before
    print(json.dumps({"status": "PASSED", "provenance": "DEMO", "method": "SQLITE_COMPILE_TIME_READ_AUDIT_V1",
        "validatorSha256": sha(Path(__file__)), "manifestIndexSha256": sha(root / "manifest-index.json"), "jobs": reports,
        "queryDataRowsRead": False, "databaseModified": False, "fieldExpressionCoverage": "READ_COLUMNS_ONLY; exact input-to-output expression mapping is not inferred", "pipelineReexecuted": False}, sort_keys=True))


if __name__ == "__main__":
    main()
