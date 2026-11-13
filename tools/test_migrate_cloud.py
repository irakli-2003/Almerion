import json
import unittest
from migrate_cloud import key, merged_rules, prepare


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.export = {
            "users": {"gm": {"username": "GM", "email": "untrusted@example.test", "passwordHash": "SECRET"},
                      "unreviewed": {"username": "Unreviewed", "passwordHash": "SECRET"}},
            "campaigns": {"one": {
                "id": "one", "name": "Campaign", "author": "Unreviewed", "joinCode": "ABC234",
                "liveToken": "SECRET-TOKEN", "members": {"unreviewed": {"username": "Unreviewed", "role": "DM"}},
                "applications": {"unreviewed": {"username": "Unreviewed"}}
            }}
        }
        self.approval = {"accounts": [
            {"username": "GM", "email": "verified-owner@example.test"},
            {"username": "Player", "email": "player@example.test"}
        ], "campaigns": {"one": {"author": "GM", "members": {"GM": "DM", "Player": "PLAYER"}}}}

    def test_only_reviewed_authority_and_no_credentials_are_migrated(self):
        result = prepare(self.export, self.approval)
        campaign = result["campaigns"]["one"]
        self.assertEqual("gm", campaign["authorKey"])
        self.assertEqual("PLAYER", campaign["members"]["player"]["role"])
        self.assertNotIn("unreviewed", campaign["members"])
        self.assertNotIn("email", result["reservedNames"]["unreviewed"])
        self.assertEqual("verified-owner@example.test", result["reservedNames"]["gm"]["email"])
        serialized = json.dumps(result)
        for forbidden in ("SECRET", "passwordHash", "applications", "liveToken", "untrusted@example.test"):
            self.assertNotIn(forbidden, serialized)
        self.assertEqual("DM", result["userCampaigns"]["gm"]["one"])
        self.assertEqual("ABC234", campaign["joinCode"])

    def test_missing_approval_and_unmapped_members_fail(self):
        self.assertRaises(ValueError, prepare, self.export, {"accounts": [], "campaigns": {}})
        self.approval["campaigns"]["one"]["members"]["Unreviewed"] = "DM"
        self.assertRaises(ValueError, prepare, self.export, self.approval)

    def test_duplicate_email_cannot_claim_two_legacy_accounts(self):
        self.approval["accounts"][1]["email"] = self.approval["accounts"][0]["email"].upper()
        self.assertRaises(ValueError, prepare, self.export, self.approval)

    def test_cutover_rules_preserve_private_files_and_close_legacy_namespace(self):
        rules = merged_rules()["rules"]
        self.assertIn("almerion-private", rules)
        self.assertIn("almerion-cloud", rules)
        self.assertFalse(rules["sessions"][".write"])
        self.assertFalse(rules["sessions"][".read"])
        maintenance = merged_rules(maintenance=True)["rules"]
        self.assertIn("almerion-private", maintenance)
        self.assertNotIn("almerion-cloud", maintenance)
        self.assertFalse(maintenance[".write"])
        self.assertEqual("a_2e_b_40_example_2e_test", key(" A.B@example.test "))


if __name__ == "__main__":
    unittest.main()
