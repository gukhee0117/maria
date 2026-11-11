"""Build the public seed contact lookup index from approved CSV exports. No DB access."""

import argparse
import csv
import hashlib
import warnings
from pathlib import Path

HEADER = "# Public seed lookup indexes, not secrets: customer ID : SHA256(customer ID + LF + login ID + LF + original email).\n"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/notification/seed-email-exemptions.sha256"


def read_rows(path, required):
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"Missing required columns in {path.name}")
        rows = list(reader)
        if any(not all(isinstance(row.get(key), str) for key in required) for row in rows):
            raise ValueError(f"Incomplete required fields in {path.name}")
        return rows


def build_index(seed_dir):
    customers = {}
    for row in read_rows(seed_dir / "customer.csv", {"customer_id", "email"}):
        customer_id = int(row["customer_id"])
        if customer_id <= 0 or customer_id in customers:
            raise ValueError("Invalid or duplicate customer ID")
        customers[customer_id] = row["email"]
    entries = {}
    local_ids = set()
    skipped = 0
    for row in read_rows(seed_dir / "customer_auth.csv", {"customer_id", "login_id", "type"}):
        if row["type"] != "LOCAL":
            continue
        customer_id = int(row["customer_id"])
        if customer_id not in customers or customer_id in local_ids:
            raise ValueError("Unknown or duplicate LOCAL customer")
        local_ids.add(customer_id)
        email, login_id = customers[customer_id], row["login_id"]
        if not login_id.strip():
            raise ValueError("Empty LOCAL login ID")
        if not email.strip():
            skipped += 1
            continue
        # Preserve exact bytes. Runtime intentionally requires an exact original-address match.
        value = f"{customer_id}\n{login_id}\n{email}".encode("utf-8")
        entries[customer_id] = hashlib.sha256(value).hexdigest()
    if skipped:
        warnings.warn(f"Excluded {skipped} LOCAL accounts without email; they are not exempt.", UserWarning)
    if not entries:
        raise ValueError("No seeded contacts found")
    return HEADER + "".join(f"{key}:{entries[key]}\n" for key in sorted(entries))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="Compare without changing any file")
    args = parser.parse_args()
    try:
        result = build_index(args.seed_dir)
        if args.check:
            if not args.output.is_file():
                parser.exit(1, "Seed contact index output is missing. Generate it from the approved seed first.\n")
            if args.output.read_text(encoding="utf-8") != result:
                parser.exit(1, "Seed contact index differs; review the approved seed input.\n")
            print("Seed contact index matches.")
        else:
            args.output.write_text(result, encoding="utf-8", newline="\n")
            print("Seed contact index generated. Review the diff before deployment.")
    except (OSError, ValueError) as failure:
        parser.exit(2, f"Seed contact generation failed: {type(failure).__name__}. Check input files and required columns.\n")


if __name__ == "__main__":
    main()
