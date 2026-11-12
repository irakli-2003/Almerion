"""Prepare an offline, explicitly reviewed migration. Never contacts Firebase."""
import argparse
import json
from pathlib import Path
import re
import secrets


def key(value):
    value = value.strip().lower()
    result = "".join(c if c.isascii() and (c.isalnum() or c == "-") else f"_{ord(c):x}_" for c in value)
    if not re.fullmatch(r"[a-z0-9_-]{1,128}", result):
        raise ValueError("An account name/email exceeds the supported cloud-key length.")
    return result


def prepare(exported, approval):
    if not isinstance(exported, dict) or not isinstance(approval, dict):
        raise ValueError("Export and approval must be JSON objects.")
    directory = exported.get("sessions", {}).get("dnd-directory", exported)
    if not isinstance(directory, dict) or not isinstance(directory.get("campaigns", {}), dict):
        raise ValueError("Export the legacy dnd-directory node, or the database root containing it.")
    if set(approval) != {"accounts", "campaigns"}:
        raise ValueError("Approval must contain accounts and campaigns, reviewed independently of the public directory.")
    if not isinstance(approval["accounts"], list) or not isinstance(approval["campaigns"], dict):
        raise ValueError("Approval accounts must be an array and campaigns must be an object.")
    accounts, emails = {}, set()
    for account in approval["accounts"]:
        if not isinstance(account, dict) or set(account) != {"username", "email"}:
            raise ValueError("Each approved account must contain only username and email.")
        if not all(isinstance(account[field], str) for field in ("username", "email")):
            raise ValueError("Approved usernames and emails must be strings.")
        name, email = account["username"].strip(), account["email"].strip().lower()
        if not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
            raise ValueError("An approved account has an invalid email.")
        name_key, email_key = key(name), key(email)
        if name_key in accounts or email_key in emails:
            raise ValueError("Approved usernames and emails must be unique.")
        accounts[name_key] = {"username": name, "email": email}
        emails.add(email_key)
    result = {"reservedNames": {}, "claimsByEmail": {}, "campaigns": {}, "joinCodes": {}, "userCampaigns": {}}
    for name_key, user in directory.get("users", {}).items():
        name = user["username"]
        if key(name) != name_key:
            raise ValueError("A legacy username/key disagrees. Review the export instead of importing it.")
        result["reservedNames"][name_key] = {"username": name}
    for name_key, account in accounts.items():
        result["reservedNames"][name_key] = dict(account)
        result["claimsByEmail"][key(account["email"])] = dict(account)
    if set(approval["campaigns"]) != set(directory.get("campaigns", {})):
        raise ValueError("Explicitly approve every exported campaign; filter a copy of the export to omit one.")
    for campaign_id, source in directory.get("campaigns", {}).items():
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", campaign_id) or source.get("id") != campaign_id:
            raise ValueError("A campaign id is invalid or disagrees with its export key.")
        approved = approval["campaigns"][campaign_id]
        if not isinstance(approved, dict) or set(approved) != {"author", "members"}:
            raise ValueError("Each campaign approval must contain author and the complete reviewed members map.")
        if not isinstance(approved["author"], str) or not isinstance(approved["members"], dict):
            raise ValueError("Approved author must be a username and members must be a username-to-role object.")
        author_key = key(approved["author"])
        if author_key not in accounts:
            raise ValueError("Every campaign author needs an approved, verified-email ownership mapping.")
        members = {}
        for username, role in approved["members"].items():
            member_key = key(username)
            if member_key not in accounts or role not in ("DM", "PLAYER") or member_key in members:
                raise ValueError("Every approved member needs a unique account mapping and a DM or PLAYER role.")
            old = source.get("members", {}).get(member_key, {})
            timestamp = old.get("joinedAtMillis", 1)
            if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp <= 0:
                timestamp = 1
            members[member_key] = {
                "username": accounts[member_key]["username"], "role": role, "joinedAtMillis": timestamp
            }
            result["userCampaigns"].setdefault(member_key, {})[campaign_id] = role
        if members.get(author_key, {}).get("role") != "DM":
            raise ValueError("Each campaign author must appear in its approved members as DM.")
        name = source.get("name")
        if not isinstance(name, str) or not 1 <= len(name) <= 256:
            raise ValueError("A campaign name is missing or too long.")
        code = source.get("joinCode", "")
        while not isinstance(code, str) or not re.fullmatch(r"[A-Z2-9]{6}", code) or code in result["joinCodes"]:
            code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6))
        author = accounts[author_key]["username"]
        result["campaigns"][campaign_id] = {
            "id": campaign_id, "name": name, "author": author, "authorKey": author_key,
            "joinCode": code, "members": members
        }
        result["joinCodes"][code] = {"id": campaign_id, "name": name, "author": author}
    return result


def merged_rules(maintenance=False):
    packaging = Path(__file__).resolve().parent.parent / "packaging"
    rules = json.loads((packaging / "firebase-cloud.rules.json").read_text(encoding="utf-8"))
    private = json.loads((packaging / "firebase-private.rules.json").read_text(encoding="utf-8"))
    if maintenance:
        rules = {"rules": {".read": False, ".write": False}}
    rules["rules"]["almerion-private"] = private["rules"]["almerion-private"]
    return rules


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, required=True, help="Local legacy directory/database JSON export")
    parser.add_argument("--approval", type=Path, required=True, help="Independently reviewed account emails and campaign roles")
    parser.add_argument("--output", type=Path, required=True, help="New almerion-cloud node JSON (never the database root)")
    parser.add_argument("--rules-output", type=Path, required=True, help="Complete merged rules for the update-only cutover")
    parser.add_argument("--maintenance-rules-output", type=Path, required=True,
                        help="Temporary shared-cloud lock, preserving private-file accounts")
    args = parser.parse_args()
    outputs = [args.output.resolve(), args.rules_output.resolve(), args.maintenance_rules_output.resolve()]
    inputs = [args.export.resolve(), args.approval.resolve()]
    if len(set(outputs)) != 3 or any(path in inputs or path.exists() for path in outputs):
        parser.error("Choose three distinct, new output files; existing files are never overwritten.")
    try:
        result = prepare(json.loads(args.export.read_text(encoding="utf-8-sig")),
                         json.loads(args.approval.read_text(encoding="utf-8-sig")))
        for path, data in zip(outputs, [result, merged_rules(), merged_rules(maintenance=True)]):
            with path.open("x", encoding="utf-8") as file:
                json.dump(data, file, ensure_ascii=False, indent=2)
                file.write("\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Migration not completed: {error}\n")
    print(f"Prepared {len(result['campaigns'])} reviewed campaigns. Nothing was deployed or uploaded.")
    print("Import data ONLY into /almerion-cloud. Pending applications and live tokens are not carried forward.")


if __name__ == "__main__":
    main()
