import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const base = new URL(process.env.FIREBASE_DATABASE_EMULATOR_URL || "http://127.0.0.1:19190");
assert.equal(base.protocol, "http:");
assert.equal(base.hostname, "127.0.0.1"); // Never send emulator admin credentials to a real database.
const projectId = "demo-almerion-private";
const rules = readFileSync(process.env.ALMERION_PRIVATE_RULES || new URL("../../../packaging/firebase-private.rules.json", import.meta.url), "utf8");
const settings = await fetch(new URL(`/.settings/rules.json?ns=${projectId}`, base), {
  method: "PUT", headers: { "Content-Type": "application/json", Authorization: "Bearer owner" }, body: rules
});
assert.equal(settings.status, 200, await settings.text());
const reset = await fetch(new URL(`/almerion-private.json?ns=${projectId}`, base), {
  method: "DELETE", headers: { Authorization: "Bearer owner" }
});
assert.equal(reset.status, 200, await reset.text());

const token = (uid, verified = true) => {
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    aud: projectId, iss: `https://securetoken.google.com/${projectId}`, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now, email: `${uid}@example.test`, email_verified: verified,
    firebase: { sign_in_provider: "password", identities: {} }
  })}.`;
};
const ref = (actor, path) => ({ actor, path });
const call = ({ actor, path }, method, value) => {
  const url = new URL(`/${path}.json?ns=${projectId}`, base);
  if (actor) url.searchParams.set("auth", actor);
  return fetch(url, { method, headers: { "Content-Type": "application/json" },
    ...(method === "PUT" ? { body: JSON.stringify(value) } : {}) });
};
const get = target => call(target, "GET");
const set = (target, value) => call(target, "PUT", value);
const assertSucceeds = async promise => {
  const response = await promise;
  assert.equal(response.status, 200, await response.text());
};
const assertFails = async promise => {
  const response = await promise;
  assert.ok([401, 403].includes(response.status), `Expected authorization denial, got ${response.status}: ${await response.text()}`);
};
const root = "almerion-private";
const db = uid => token(uid);
const owner = db("owner"), player = db("player"), attacker = db("attacker");
const anonymous = null;
const identity = uid => ({
  uid, email: `${uid}@example.test`, name: uid, encryptionKey: "E".repeat(600), signingKey: "S".repeat(600)
});
const vault = uid => ({ version: 1, identity: identity(uid), passwordBox: "encrypted", recoveryBox: "encrypted-recovery" });
const accountPath = uid => `${root}/accounts/${uid}`;
const identityPath = uid => `${root}/identities/${uid}`;
try {
  for (const [uid, database] of [["owner", owner], ["player", player], ["attacker", attacker]]) {
    await assertSucceeds(set(ref(database, accountPath(uid)), vault(uid)));
    await assertSucceeds(set(ref(database, identityPath(uid)), identity(uid)));
  }
  await assertSucceeds(get(ref(owner, accountPath("owner"))));
  await assertFails(get(ref(anonymous, accountPath("owner"))));
  await assertFails(get(ref(player, accountPath("owner"))));
  await assertFails(get(ref(anonymous, `${root}/identities`)));
  await assertFails(set(ref(anonymous, accountPath("owner")), vault("owner")));
  await assertFails(set(ref(anonymous, accountPath("probe-absent")), null));
  await assertFails(set(ref(anonymous, identityPath("probe-absent")), null));
  await assertFails(set(ref(attacker, accountPath("owner")), vault("owner")));
  await assertFails(set(ref(owner, accountPath("owner")), { ...vault("owner"), identity: { ...identity("owner"), signingKey: "X".repeat(600) } }));
  await assertFails(set(ref(owner, identityPath("owner")), { ...identity("owner"), signingKey: "X".repeat(600) }));
  await assertFails(set(ref(attacker, identityPath("owner")), identity("owner")));
  await assertFails(set(ref(owner, accountPath("owner")), null));
  await assertSucceeds(set(ref(owner, accountPath("owner")), { ...vault("owner"), passwordBox: "recovered-password-box" }));
  await assertSucceeds(get(ref(player, `${root}/identities`)));

  const unverified = token("unverified", false);
  await assertFails(set(ref(unverified, accountPath("unverified")), vault("unverified")));
  const path = `${root}/requests/owner/player/file-one`;
  const request = { fileId: "file-one", campaignId: "campaign", fileName: "Secret.txt", requesterUid: "player", requestedAt: Date.now(), status: "pending" };
  await assertSucceeds(set(ref(player, path), request));
  await assertSucceeds(get(ref(owner, `${root}/requests/owner`)));
  await assertSucceeds(get(ref(player, path)));
  await assertFails(get(ref(player, `${root}/requests/owner`)));
  await assertFails(get(ref(attacker, path)));
  await assertFails(set(ref(attacker, path), request));
  await assertFails(set(ref(player, path), { ...request, status: "approved" }));
  await assertFails(set(ref(owner, path), { ...request, requesterUid: "attacker", status: "approved" }));
  await assertSucceeds(set(ref(owner, path), { ...request, status: "approved" }));
  await assertSucceeds(set(ref(player, path), { ...request, requestedAt: Date.now(), status: "pending" }));
  await assertFails(set(ref(player, path), { ...request, status: "pending" }));
  await assertFails(set(ref(anonymous, path), request));
  await assertFails(set(ref(player, `${root}/requests/owner/player/wrong-id`), request));
  await assertFails(set(ref(owner, path), null));
  await assertSucceeds(set(ref(player, `${root}/outgoing/player/file-one`), { ownerUid: "owner" }));
  await assertFails(get(ref(owner, `${root}/outgoing/player`)));
  await assertFails(set(ref(attacker, `${root}/outgoing/player/file-one`), { ownerUid: "attacker" }));
  console.log("Private Firebase rules: authenticated identities, immutable keys, private vaults, and request authorization passed.");
} finally {
  // The bounded validation command stops this dedicated emulator after the checks.
}
