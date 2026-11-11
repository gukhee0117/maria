"""Build the public seed contact lookup index from approved CSV exports. No DB access."""

import argparse
import csv
import hashlib
from pathlib import Path

HEADER = "# Public seed lookup indexes, not secrets: customer ID : SHA256(customer ID + LF + login ID + LF + original email).\n"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/notification/seed-email-exemptions.sha256"


def read_rows(path, required):
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"Missing required columns in {path.name}")
        return list(reader)


def build_index(seed_dir):
    customers = {}
    for row in read_rows(seed_dir / "customer.csv", {"customer_id", "email"}):
        customer_id = int(row["customer_id"])
        if customer_id <= 0 or customer_id in customers:
            raise ValueError("Invalid or duplicate customer ID")
        customers[customer_id] = row["email"]
    entries = {}
    for row in read_rows(seed_dir / "customer_auth.csv", {"customer_id", "login_id", "type"}):
        if row["type"] != "LOCAL":
            continue
        customer_id = int(row["customer_id"])
        if customer_id not in customers or customer_id in entries:
            raise ValueError("Unknown or duplicate LOCAL customer")
        email, login_id = customers[customer_id], row["login_id"]
        if not login_id.strip():
            raise ValueError("Empty LOCAL login ID")
        if not email.strip():
            continue
        # Preserve exact bytes. Runtime intentionally requires an exact original-address match.
        value = f"{customer_id}\n{login_id}\n{email}".encode("utf-8")
        entries[customer_id] = hashlib.sha256(value).hexdigest()
    if not entries:
        raise ValueError("No seeded contacts found")
    return HEADER + "".join(f"{key}:{entries[key]}\n" for key in sorted(entries))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="Compare without changing any file")
    args = parser.parse_args()
    result = build_index(args.seed_dir)
    if args.check:
        if args.output.read_text(encoding="utf-8") != result:
            parser.exit(1, "Seed contact index differs; review the approved seed input.\n")
        print("Seed contact index matches.")
    else:
        args.output.write_text(result, encoding="utf-8", newline="\n")
        print("Seed contact index generated. Review the diff before deployment.")


if __name__ == "__main__":
    main()
