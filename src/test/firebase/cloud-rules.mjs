import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const base = new URL(process.env.FIREBASE_DATABASE_EMULATOR_URL || "http://127.0.0.1:19190");
assert.equal(base.protocol, "http:");
assert.equal(base.hostname, "127.0.0.1");
const ns = "demo-almerion-cloud";
const root = "almerion-cloud";
const rules = JSON.parse(readFileSync(new URL("../../../packaging/firebase-cloud.rules.json", import.meta.url), "utf8"));
const privateRules = JSON.parse(readFileSync(new URL("../../../packaging/firebase-private.rules.json", import.meta.url), "utf8"));
rules.rules["almerion-private"] = privateRules.rules["almerion-private"];
const settings = await fetch(new URL(`/.settings/rules.json?ns=${ns}`, base), {
  method: "PUT", headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
  body: JSON.stringify(rules)
});
assert.equal(settings.status, 200, await settings.text());
const token = (uid, verified = true, email = `${uid}@example.test`) => {
  const now = Math.floor(Date.now() / 1000);
  const encode = x => Buffer.from(JSON.stringify(x)).toString("base64url");
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    aud: ns, iss: `https://securetoken.google.com/${ns}`, sub: uid, user_id: uid,
    iat: now, exp: now + 3600, auth_time: now, email, email_verified: verified,
    firebase: { sign_in_provider: "password", identities: {} }
  })}.`;
};
async function call(actor, method, path, value) {
  const url = new URL(`/${path}.json?ns=${ns}`, base);
  const headers = { "Content-Type": "application/json" };
  if (actor === "admin") headers.Authorization = "Bearer owner";
  else if (actor) url.searchParams.set("auth", actor);
  return fetch(url, { method, headers, ...(value === undefined ? {} : { body: JSON.stringify(value) }) });
}
async function ok(promise) {
  const response = await promise;
  assert.equal(response.status, 200, await response.text());
}
async function denied(promise) {
  const response = await promise;
  assert.ok([401, 403].includes(response.status), `Expected denial: ${response.status} ${await response.text()}`);
}
const set = (a, p, v) => call(a, "PUT", p, v);
const patch = (a, v) => call(a, "PATCH", root, v);
const get = (a, p) => call(a, "GET", p);
const owner = token("owner"), dm = token("codm"), player = token("player"), stranger = token("stranger");
const pending = token("pending"), second = token("second");
const profile = (uid, username = uid) => ({ uid, email: `${uid}@example.test`, username });
const enroll = (uid, username = uid, extra = {}) => patch(token(uid), {
  [`profiles/${username}`]: { ...profile(uid, username), ...extra }, [`accountKeys/${uid}`]: username
});
await ok(set("admin", "", null));
await ok(set("admin", `${root}/reservedNames/old_20_gm`, { email: "migrant@example.test", username: "Old GM" }));
await ok(set("admin", `${root}/claimsByEmail/migrant_40_example_2e_test`, { email: "migrant@example.test", username: "Old GM" }));
await denied(get(stranger, `${root}/claimsByEmail/migrant_40_example_2e_test`));
await ok(get(token("migrant"), `${root}/claimsByEmail/migrant_40_example_2e_test`));
await ok(patch(token("migrant"), {
  "profiles/old_20_gm": profile("migrant", "Old GM"), "accountKeys/migrant": "old_20_gm"
}));
await ok(set("admin", `${root}/reservedNames/unreviewed`, { username: "Unreviewed" }));
await denied(enroll("unreviewed", "unreviewed"));
await ok(set("admin", `${root}/reservedNames/owner`, { email: "owner@example.test", username: "owner" }));
await denied(enroll("stranger", "owner"));
await denied(enroll("owner", "owner", { passwordHash: "must-not-be-uploaded" }));
for (const uid of ["owner", "codm", "player", "stranger", "pending", "second"]) await ok(enroll(uid));
await denied(enroll("owner", "another-name"));
await denied(set(owner, `${root}/profiles/owner/email`, "stranger@example.test"));
await denied(set(owner, `${root}/accountKeys/owner`, null));
await denied(get(null, `${root}/profiles/owner`));
await denied(get(player, `${root}/accountKeys/owner`));
await denied(get(token("unverified", false), `${root}/profiles/owner`));
await denied(set(null, "sessions/legacy-session", "attacker"));
await denied(get(null, "sessions/dnd-directory/users/owner"));
await denied(get(owner, "sessions/dnd-directory/users/owner"));

const member = (username, role = "PLAYER") => ({ username, role, joinedAtMillis: Date.now() });
const application = (username, joinCode = "ABC234") => ({ username, joinCode, requestedAtMillis: Date.now() });
const invite = { id: "campaign", name: "Campaign", author: "owner" };
const campaign = {
  ...invite, authorKey: "owner", joinCode: "ABC234", members: { owner: member("owner", "DM") }
};
await ok(patch(owner, {
  "campaigns/campaign": campaign, "joinCodes/ABC234": invite, "userCampaigns/owner/campaign": "DM"
}));
await denied(get(stranger, `${root}/campaigns/campaign`));
await ok(get(stranger, `${root}/campaigns/campaign/id`));
await denied(get(stranger, `${root}/campaigns/campaign/author`));
await ok(get(stranger, `${root}/campaigns/campaign/members/stranger`));
await denied(get(player, `${root}/userCampaigns/owner`));
await denied(get(stranger, `${root}/joinCodes`));
await ok(get(stranger, `${root}/joinCodes/ABC234`));
await denied(set(stranger, `${root}/campaigns/campaign`, { ...campaign, author: "stranger", authorKey: "stranger" }));
await denied(set(stranger, `${root}/campaigns/campaign/members/stranger`, member("stranger", "DM")));
await denied(set(stranger, `${root}/campaigns/campaign/applications/owner`, application("owner")));
await denied(set(player, `${root}/campaigns/campaign/applications/player`, application("player", "WRONG2")));
async function apply(actor, username) {
  await ok(patch(actor, {
    [`campaigns/campaign/applications/${username}`]: application(username),
    [`userApplications/${username}/campaign`]: invite
  }));
}
async function accept(actor, username, role = "PLAYER") {
  await ok(patch(actor, {
    [`campaigns/campaign/members/${username}`]: member(username, role),
    [`campaigns/campaign/applications/${username}`]: null,
    [`userApplications/${username}/campaign`]: null,
    [`userCampaigns/${username}/campaign`]: role
  }));
}
await apply(dm, "codm");
await accept(owner, "codm", "DM");
await Promise.all([apply(player, "player"), apply(pending, "pending"), apply(second, "second")]);
await denied(get(pending, `${root}/campaigns/campaign`));
await ok(get(pending, `${root}/campaigns/campaign/applications/pending`));
await ok(get(pending, `${root}/userApplications/pending`));
await denied(set(dm, `${root}/campaigns/campaign/members/pending`, member("pending", "DM")));
await denied(set(dm, `${root}/campaigns/campaign`, { ...campaign, members: { codm: member("codm", "DM") } }));
await Promise.all([accept(dm, "player"), accept(owner, "second")]);
let current = await (await get(owner, `${root}/campaigns/campaign`)).json();
assert.ok(current.members.player && current.members.second && current.applications.pending);
await denied(set(player, `${root}/campaigns/campaign/members/player/role`, "DM"));
await denied(set(dm, `${root}/campaigns/campaign/members/owner`, null));
await denied(set(owner, `${root}/campaigns/campaign/members/owner`, null));
await denied(set(player, `${root}/campaigns/campaign/name`, "stolen"));
await denied(set(player, `${root}/userCampaigns/player/campaign`, "DM"));
await denied(set(stranger, `${root}/userCampaigns/stranger/campaign`, "DM"));
await ok(patch(owner, {
  "campaigns/campaign/members/second/role": "DM", "userCampaigns/second/campaign": "DM"
}));
await denied(set(dm, `${root}/campaigns/campaign/members/second`, null));

const id = "a".repeat(64), another = "b".repeat(64);
const live = (hostUid, campaignId = "campaign") => ({
  hostUid, campaignId, expiresAt: Date.now() + 43_200_000, snapshot: "encrypted-only"
});
await denied(set(player, `almerion-live/${id}`, live("player")));
await denied(set(stranger, `almerion-live/${id}`, live("stranger")));
await denied(set(dm, `almerion-live/${id}`, live("owner")));
await ok(set(owner, `almerion-live/${id}`, live("owner")));
const chat = `almerion-chat/${id}`;
const connection = "0";
const wire = { connection: "1".repeat(36), id: "2".repeat(36), key: "wrapped-key", data: "encrypted-message" };
await ok(set(owner, `${chat}/publicKey`, "host-public-key"));
await ok(get(player, `${chat}/publicKey`));
await denied(get(null, `${chat}/publicKey`));
await denied(get(stranger, `${chat}/publicKey`));
await denied(get(pending, `${chat}/publicKey`));
await denied(get(player, chat));
await denied(get(dm, chat));
await denied(set(player, `${chat}/publicKey`, "replacement"));
await ok(set(player, `${chat}/inbox/player/${connection}`, wire));
await ok(get(player, `${chat}/inbox/player/${connection}`));
await denied(get(dm, `${chat}/inbox/player/${connection}`));
await denied(set(player, `${chat}/inbox/player/${connection}`, { ...wire, connection: "3".repeat(36) }));
await denied(set(player, `${chat}/inbox/player/32`, wire));
const claims = await Promise.all(["3", "4"].map(c =>
  set(player, `${chat}/inbox/player/1`, { ...wire, connection: c.repeat(36) })));
assert.equal(claims.filter(r => r.status === 200).length, 1, "Only one device may claim a slot");
assert.equal(claims.filter(r => [401, 403].includes(r.status)).length, 1);
await ok(set(player, `${chat}/inbox/player/1`, null));
await ok(set(player, `${chat}/inbox/player/1`, { ...wire, connection: "5".repeat(36) }));
await denied(set(player, `${chat}/inbox/owner/${connection}`, wire));
await denied(set(null, `${chat}/inbox/player/${connection}`, wire));
await denied(get(player, `${chat}/inbox`));
await denied(get(dm, `${chat}/inbox`));
await ok(get(owner, `${chat}/inbox`));
await denied(set(player, `${chat}/outbox/player/${connection}`, wire));
await ok(set(owner, `${chat}/outbox/player/${connection}`, wire));
await denied(set(owner, `${chat}/outbox/player/${connection}`, { ...wire, id: "6".repeat(36) }));
await ok(get(player, `${chat}/outbox/player/${connection}`));
await denied(get(dm, `${chat}/outbox/player/${connection}`));
await denied(get(second, `${chat}/outbox/player/${connection}`));
await denied(set(player, `${chat}/inbox/player/${connection}`, { ...wire, text: "plaintext" }));
await denied(set(player, `${chat}/inbox/player/${connection}`, { ...wire, data: "x".repeat(32769) }));
await denied(set(owner, `${chat}/privateNotes`, "not-a-chat-field"));
const guestKey = "c".repeat(64), guest = `${chat}/guests/${guestKey}`;
await ok(set(owner, `${chat}/guestKey`, guestKey));
await ok(set(owner, `${guest}/publicKey`, "ephemeral-guest-key"));
await ok(get(null, `${guest}/publicKey`));
await denied(get(null, `${chat}/guestKey`));
await denied(get(null, `${chat}/guests`));
await denied(get(null, `${chat}/guests/${"d".repeat(64)}/publicKey`));
await denied(set(null, `${guest}/publicKey`, "attacker-key"));
await denied(set(null, `${chat}/guestKey`, "d".repeat(64)));
await ok(set(null, `${guest}/inbox/guest/0`, wire));
await denied(set(null, `${guest}/inbox/guest/0`, { ...wire, connection: "3".repeat(36) }));
await denied(set(null, `${guest}/inbox/guest/32`, wire));
await denied(set(null, `${guest}/inbox/fake/0`, wire));
await denied(set(null, `${guest}/inbox/guest/1`, { ...wire, data: "x".repeat(32769) }));
await denied(set(null, `${guest}/inbox/guest/1`, { ...wire, actor: "gm" }));
await denied(set(null, `${guest}/outbox/guest/0`, wire));
await ok(set(owner, `${guest}/outbox/guest/0`, wire));
await ok(get(null, `${guest}/outbox/guest/0`));
await denied(set(owner, `${guest}/outbox/guest/0`, { ...wire, id: "6".repeat(36) }));
await denied(set(null, `almerion-live/${id}/snapshot`, "guest-cannot-change-snapshots"));
await denied(set(null, `${root}/campaigns/campaign/name`, "guest-cannot-administer"));
await ok(set(null, `${guest}/outbox/guest/0`, null));
await ok(get(null, `almerion-live/${id}/snapshot`));
await denied(get(null, `almerion-live/${id}`));
await denied(get(null, "almerion-live"));
await denied(set(null, `almerion-live/${id}/snapshot`, "corrupt"));
await denied(set(player, `almerion-live/${id}/snapshot`, "corrupt"));
await denied(set(dm, `almerion-live/${id}`, null));
await denied(set(dm, `almerion-live/${id}`, live("codm")));
await denied(set(owner, `almerion-live/${id}`, live("owner", "another-campaign")));
await denied(set(owner, `almerion-live/${id}`, { ...live("owner"), expiresAt: Date.now() + 172_800_000 }));
await ok(set(dm, `almerion-live/${another}`, live("codm")));
const otherChat = `almerion-chat/${another}`;
await ok(set(dm, `${otherChat}/publicKey`, "co-gm-key"));
await ok(set(dm, `${otherChat}/guestKey`, guestKey));
await ok(set(dm, `${otherChat}/guests/${guestKey}/publicKey`, "co-gm-guest-key"));
await ok(set(player, `${otherChat}/inbox/player/0`, wire));
await ok(set(dm, `${otherChat}/outbox/player/0`, wire));
await ok(patch(owner, {
  "campaigns/campaign/members/codm/role": "PLAYER", "userCampaigns/codm/campaign": "PLAYER"
}));
await denied(set(dm, `almerion-live/${another}/snapshot`, "after-demotion"));
await denied(get(null, `almerion-live/${another}/snapshot`));
await denied(get(player, `${otherChat}/publicKey`));
await denied(get(player, `${otherChat}/inbox/player/0`));
await denied(get(player, `${otherChat}/outbox/player/0`));
await denied(set(player, `${otherChat}/inbox/player/0`, wire));
await denied(get(null, `${otherChat}/guests/${guestKey}/publicKey`));
await denied(set(null, `${otherChat}/guests/${guestKey}/inbox/guest/0`, wire));
await ok(set("admin", `almerion-live/${id}/expiresAt`, Date.now() - 1));
await denied(get(null, `almerion-live/${id}/snapshot`));
await denied(get(player, `${chat}/outbox/player/${connection}`));
await denied(set(player, `${chat}/inbox/player/${connection}`, wire));
await denied(get(owner, chat));
await denied(get(null, `${guest}/publicKey`));
await denied(set(null, `${guest}/inbox/guest/0`, wire));
await ok(set("admin", `almerion-live/${id}/expiresAt`, Date.now() + 43_200_000));
await ok(get(null, `almerion-live/${id}/snapshot`));
await ok(set(owner, chat, null));
await ok(set(owner, `almerion-live/${id}`, null));
await denied(get(null, `almerion-live/${id}/snapshot`));

await ok(set(owner, `${root}/campaigns/campaign/liveToken`, "read-only-player-token"));
await denied(get(pending, `${root}/campaigns/campaign/liveToken`));
await denied(set(player, `${root}/campaigns/campaign/liveToken`, "fake"));
await denied(set(player, `${root}/campaigns/campaign`, null));
await ok(patch(player, { "campaigns/campaign/members/player": null, "userCampaigns/player/campaign": null }));
await denied(get(player, `${root}/campaigns/campaign`));
await denied(set(owner, `${root}/campaigns/campaign/members/player`, member("player")));
await denied(set(dm, `${root}/campaigns/campaign`, null));
const maintenance = { rules: { ".read": false, ".write": false,
  "almerion-private": privateRules.rules["almerion-private"] } };
async function deploy(value) {
  const response = await fetch(new URL(`/.settings/rules.json?ns=${ns}`, base), {
    method: "PUT", headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
    body: JSON.stringify(value)
  });
  assert.equal(response.status, 200, await response.text());
}
await deploy(maintenance);
await denied(get(owner, `${root}/profiles/owner`));
await denied(set(owner, `${root}/campaigns/campaign/name`, "during-maintenance"));
await ok(get(owner, "almerion-private/accounts/owner"));
await denied(get(player, "almerion-private/accounts/owner"));
await deploy(rules);
await ok(get(owner, `${root}/profiles/owner`));
console.log("Cloud rules: verified profile ownership, private membership reads, role enforcement, atomic updates, and host-only writes passed.");
