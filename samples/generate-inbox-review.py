"""Print a synthetic SMS XML export covering organizer flows. No device access."""

from datetime import datetime, timedelta, timezone
import sys
from xml.etree import ElementTree as ET


def main():
    now = datetime.now(timezone.utc)
    due = (now + timedelta(days=3)).date().isoformat()
    travel = (now + timedelta(days=8)).date().isoformat()
    examples = [
        ("ZZ-EXBANK-T", 1, "INR 1,249.00 debited from your A/c XX0000 via UPI.", "1"),
        ("ZZ-EXPOWR-T", 1, f"Your electricity bill of INR 880 is due on {due}.", "1"),
        ("ZZ-EXRAIL-S", 1, f"IRCTC: Train 12345. Journey date {travel}. Departure time 16:30.", "1"),
        ("ZZ-EXBANK-S", 10, "Your verification code is 424242. Fictional example.", "1"),
        ("ZZ-EXSHOP-P", 40, "Get 40% off with our fictional festival offer.", "1"),
        ("+10000000001", 1, "Final warning. Your account will be blocked. Update your KYC at https://192.0.2.1/fixture-only", "1"),
        ("Fictional contact", 0, "Got it. See you soon.", "2"),
    ]
    root = ET.Element("smses", count=str(len(examples)))
    for index, (address, age, body, direction) in enumerate(examples):
        stamp = now - timedelta(days=age, minutes=index)
        ET.SubElement(root, "sms", address=address, date=str(int(stamp.timestamp() * 1000)), type=direction, body=body)
    ET.indent(root, space="  ")
    ET.ElementTree(root).write(sys.stdout.buffer, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
