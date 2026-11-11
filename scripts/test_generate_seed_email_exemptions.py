import csv
import hashlib
import tempfile
import unittest
from pathlib import Path

from generate_seed_email_exemptions import build_index


class SeedIndexTest(unittest.TestCase):
    def write_csv(self, directory, name, header, rows):
        with (directory / name).open("w", encoding="utf-8-sig", newline="") as target:
            writer = csv.writer(target)
            writer.writerow(header)
            writer.writerows(rows)

    def test_exact_contact_bytes_and_numeric_order_and_exclusions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_csv(root, "customer.csv", ["customer_id", "email"],
                           [[10, "Upper@example.com"], [2, "two@example.com"], [3, ""], [4, "social@example.com"]])
            self.write_csv(root, "customer_auth.csv", ["customer_id", "login_id", "type"],
                           [[10, "user10", "LOCAL"], [3, "user3", "LOCAL"], [4, "social", "GOOGLE"], [2, "user2", "LOCAL"]])
            result = build_index(root).splitlines()[1:]
            self.assertEqual(result, [
                "2:" + hashlib.sha256(b"2\nuser2\ntwo@example.com").hexdigest(),
                "10:" + hashlib.sha256(b"10\nuser10\nUpper@example.com").hexdigest(),
            ])

    def test_duplicate_customer_and_duplicate_local_auth_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_csv(root, "customer.csv", ["customer_id", "email"], [[1, "a@example.com"], [1, "b@example.com"]])
            self.write_csv(root, "customer_auth.csv", ["customer_id", "login_id", "type"], [[1, "user1", "LOCAL"]])
            with self.assertRaises(ValueError):
                build_index(root)
            self.write_csv(root, "customer.csv", ["customer_id", "email"], [[1, "a@example.com"]])
            self.write_csv(root, "customer_auth.csv", ["customer_id", "login_id", "type"], [[1, "user1", "LOCAL"], [1, "user2", "LOCAL"]])
            with self.assertRaises(ValueError):
                build_index(root)

    def test_missing_columns_and_unknown_auth_customer_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_csv(root, "customer.csv", ["customer_id"], [[1]])
            with self.assertRaises(ValueError):
                build_index(root)
            self.write_csv(root, "customer.csv", ["customer_id", "email"], [[1, "a@example.com"]])
            self.write_csv(root, "customer_auth.csv", ["customer_id", "login_id", "type"], [[2, "user2", "LOCAL"]])
            with self.assertRaises(ValueError):
                build_index(root)


if __name__ == "__main__":
    unittest.main()
