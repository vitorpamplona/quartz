/**
 * Concord app-event interop vectors, generated against Armada (soapbox.pub's Concord client).
 *
 * Armada is AGPL-3.0, so it is never copied into Quartz: `gen.sh` copies THIS file into a scratch
 * Armada checkout (`$ARMADA_DIR/src/__quartz_vectors__/`, so its `@/…` imports resolve), runs it
 * with Armada's own vitest config, and deletes it again. Only the JSON it writes (protocol messages
 * and what Armada made of them) is committed, to
 * `quartz/src/commonTest/resources/concord/app-events/`.
 *
 * Two files come out:
 *
 *  - `armada.json` (THEIRS → US): rumors Armada's own send paths produce — `useSendMessage` for
 *    chat (the mutation every composer button calls, mounted with renderHook), `useTypingPublisher`
 *    for typing, and Armada's edition/Guestbook builders for the Control and Guestbook planes —
 *    each with `expect`, what Armada's own read path decided about it (`openChatBatch` +
 *    `foldTimeline`, `openControlWraps` + `foldControlState`, `coalesceGuestbook`, the typing
 *    reader, `channelsView` for channel order).
 *  - `ours-read-by-armada.json` (OURS → THEM): the rumors Quartz's builders produce
 *    (`$OURS_IN`, dumped by Quartz's `ConcordAppEventOursDump` jvmTest), sealed and wrapped by
 *    their author and run through the same Armada read path; `armada` records what it understood.
 *
 * Every rumor is sealed by its author's key and wrapped at its plane's address, then opened with
 * Armada's own functions, so binding, `ms` and plane-kind checks are Armada's, not a re-statement.
 * Only four seams are replaced, as Armada's own hook tests replace them: the relay pool (an
 * in-memory one), the signed-in user, the app config and the Control fold the send hooks read.
 *
 * Env: VECTORS_OUT (output directory), OURS_IN (optional, Quartz's ours.rumors.json),
 *      ARMADA_REV (recorded as the source).
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook } from "@testing-library/react";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { matchFilters, type Filter } from "nostr-tools/filter";
import { getConversationKey, decrypt as nip44Decrypt, encrypt as nip44Encrypt } from "nostr-tools/nip44";
import { nip19 } from "nostr-tools";
import { finalizeEvent, getPublicKey, type EventTemplate, type NostrEvent } from "nostr-tools/pure";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createElement, type ReactNode } from "react";
import { expect, it, vi } from "vitest";

// ── seams ────────────────────────────────────────────────────────────────────

const h = vi.hoisted(() => ({
  relays: new Map<string, unknown>(),
  user: undefined as unknown,
  folded: undefined as unknown,
}));

class MemoryRelay {
  stored: NostrEvent[] = [];
  async query(filters: Filter[]): Promise<NostrEvent[]> {
    return this.stored.filter((ev) => matchFilters(filters, ev));
  }
  async event(ev: NostrEvent): Promise<void> {
    if (!this.stored.some((e) => e.id === ev.id)) this.stored.push(ev);
  }
  async *req(): AsyncGenerator<never> {
    // No live subscriptions: every read here is a one-shot query.
  }
  async close(): Promise<void> {}
}

function relayAt(url: string): MemoryRelay {
  let r = h.relays.get(url) as MemoryRelay | undefined;
  if (!r) h.relays.set(url, (r = new MemoryRelay()));
  return r;
}

const pool = {
  relay: (url: string) => relayAt(url),
  group: (urls: string[]) => ({
    query: async (filters: Filter[]) => (await Promise.all(urls.map((u) => relayAt(u).query(filters)))).flat(),
    event: async (ev: NostrEvent) => {
      await Promise.all(urls.map((u) => relayAt(u).event(ev)));
    },
    req: () => relayAt(urls[0]).req(),
  }),
  query: async (filters: Filter[]) => (await Promise.all([...h.relays.values()].map((r) => (r as MemoryRelay).query(filters)))).flat(),
  event: async (ev: NostrEvent) => {
    await Promise.all([...h.relays.values()].map((r) => (r as MemoryRelay).event(ev)));
  },
  req: () => relayAt(RELAY).req(),
};

vi.mock("@nostrify/react", () => ({ useNostr: () => ({ nostr: pool }) }));
vi.mock("@/hooks/useAccountSecret", () => ({ useAccountSecret: () => undefined }));
vi.mock("@/hooks/useCurrentUser", () => ({ useCurrentUser: () => ({ user: h.user }) }));
vi.mock("@/hooks/useAppContext", () => ({
  useAppContext: () => ({ config: { appRelays: [RELAY], communityRelays: [RELAY], dmTypingIndicators: true } }),
}));
vi.mock("@/hooks/useEventStore", () => ({
  useEventStore: () => Promise.resolve({ query: async () => [], event: async () => undefined }),
}));
vi.mock("@/hooks/useRemoveRailKey", () => ({ useRemoveRailKey: () => () => undefined }));
vi.mock("@/concord/hooks/useControlPlane", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useControlFold: () => ({ data: h.folded }),
  useDissolved: () => ({ data: null }),
}));

// ── fixture: fixed keys, one community, one channel, a fixed clock ───────────

const RELAY = "wss://vectors.invalid";
const SK: Record<string, string> = {
  owner: "11".repeat(32),
  admin: "22".repeat(32),
  mod: "33".repeat(32),
  alice: "44".repeat(32),
  bob: "55".repeat(32),
};
const PK: Record<string, string> = Object.fromEntries(Object.entries(SK).map(([k, v]) => [k, getPublicKey(hexToBytes(v))]));
const NAME_OF = new Map(Object.entries(PK).map(([k, v]) => [v, k]));
const OWNER_SALT = "a5".repeat(32);
const ROOT = "a6".repeat(32);
const CONTROL_ROOT = "a7".repeat(32);
const GENERAL = "9a".repeat(32);
const ADMIN_ROLE = "a1".repeat(32);
const MOD_ROLE = "a2".repeat(32);
/** 2026-01-01T00:00:00Z: every rumor is dated T0 + n seconds; the reader's clock reads T0 + 600 s. */
const T0 = 1767225600;
const NOW_MS = (T0 + 600) * 1000;

const signerOf = (pubkey: string) => {
  const name = NAME_OF.get(pubkey);
  if (!name) throw new Error(`no key for ${pubkey}`);
  const sk = hexToBytes(SK[name]);
  return {
    getPublicKey: async () => pubkey,
    signEvent: async (t: EventTemplate) => finalizeEvent(t, sk),
    nip44: {
      encrypt: async (pk: string, plaintext: string) => nip44Encrypt(plaintext, getConversationKey(sk, pk)),
      decrypt: async (pk: string, ciphertext: string) => nip44Decrypt(ciphertext, getConversationKey(sk, pk)),
    },
  };
};
const userOf = (name: string) => ({ pubkey: PK[name], method: "nsec", signer: signerOf(PK[name]) });

type Rumor = { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string };
const rumorOf = (e: { id: string; pubkey: string; created_at: number; kind: number; tags: string[][]; content: string }): Rumor => ({
  id: e.id,
  pubkey: e.pubkey,
  created_at: e.created_at,
  kind: e.kind,
  tags: e.tags,
  content: e.content,
});

it("generates the Concord app-event vectors", { timeout: 120_000 }, async () => {
  vi.useFakeTimers({ toFake: ["Date"], now: NOW_MS });
  const out = process.env.VECTORS_OUT!;
  mkdirSync(out, { recursive: true });

  const derive = await import("@/concord/lib/derive");
  const control = await import("@/concord/lib/control");
  const edition = await import("@/concord/lib/edition");
  const stream = await import("@/concord/lib/stream");
  const chat = await import("@/concord/lib/chat");
  const gb = await import("@/concord/lib/guestbook");
  const roles = await import("@/concord/lib/roles");
  const { guestbookFoldOptions } = await import("@/concord/lib/memberlist");
  const { mayHideIn } = await import("@/concord/lib/channelJudges");
  const { channelsView } = await import("@/concord/lib/community");
  const { withChannelPosition } = await import("@/concord/lib/channelOrder");
  const { inlineReplyQuoteId } = await import("@/lib/quoteReply");
  const { reactionContentKey, customEmojiReactionTags } = await import("@/hooks/useReactions");
  const { isRenderableReaction } = await import("@/concord/lib/reactionContent");
  const { isPlausiblyLive } = await import("@/lib/liveSignal");
  const { citationFor } = await import("@/concord/hooks/useControlPlane");
  const { useSendMessage } = await import("@/concord/hooks/useChannel");
  const { useTypingPublisher } = await import("@/concord/hooks/useTyping");
  const { buildPollTags } = await import("@/lib/polls");
  const kinds = await import("@/concord/lib/kinds");

  const id = derive.communityIdOf(derive.hex32(PK.owner), hexToBytes(OWNER_SALT));
  const controlPk = derive.controlSignerGroupKey(hexToBytes(CONTROL_ROOT), id, 0n).pk;
  const community = {
    id,
    idHex: bytesToHex(id),
    owner: PK.owner,
    ownerSalt: hexToBytes(OWNER_SALT),
    root: hexToBytes(ROOT),
    rootEpoch: 0n,
    controlPk,
    controlRoot: hexToBytes(CONTROL_ROOT),
    heldRoots: [{ epoch: 0n, key: hexToBytes(ROOT), controlPk }],
    privateChannels: [],
    relays: [RELAY],
    name: "Vectors",
  };
  const communityJson = { id: community.idHex, owner: PK.owner, owner_salt: OWNER_SALT, root: ROOT, channel: GENERAL, epoch: 0, now_ms: NOW_MS };

  // ── the Armada read path, for any rumor ────────────────────────────────────

  const controlWrite = control.currentControlWriteGroup(community as never);
  const guestbookGroup = gb.currentGuestbookGroup(community as never);

  async function foldOf(editions: Rumor[]) {
    const wraps = [];
    for (const r of editions) wraps.push(await control.sealEdition(r as never, controlWrite, signerOf(r.pubkey)));
    const parsed = control.openControlWraps(wraps, control.controlGroups(community as never));
    return { folded: control.foldControlState(parsed, id, PK.owner), parsed };
  }

  function channelOf(folded: unknown) {
    const ch = channelsView(community as never, folded as never).find((c) => c.idHex === GENERAL);
    if (!ch) throw new Error("general channel not in the fold");
    return ch;
  }

  async function chatWrap(r: Rumor, ch: { current: { group: unknown } }, ephemeral = false) {
    const seal = await stream.sealRumor(r as never, kinds.KIND_SEAL_ENCRYPTED, ch.current.group as never, signerOf(r.pubkey));
    return stream.wrapSeal(seal, ch.current.group as never, ephemeral ? { ephemeral: true } : undefined);
  }

  /** What Armada's chat ingest + fold make of `rumors` (in order), one reading per rumor. */
  async function readChat(rumors: Rumor[], folded: unknown) {
    const ch = channelOf(folded);
    const f = folded as { banned: Set<string>; heads: Map<string, unknown>; ownerHex: string };
    const moderation = {
      banned: f.banned,
      // The composition `useChatModeration` makes (useChannel.ts).
      canDelete: (deleter: string, author: string, action?: { citation?: unknown; ms: number }, channelIdHex?: string) =>
        mayHideIn(folded as never, deleter, author, channelIdHex) && control.citationSatisfied(f as never, id, deleter, action?.citation as never),
      canSetTimer: (author: string) => roles.isAuthorized((folded as never as { roster: never }).roster, author, f.ownerHex, roles.Permissions.MANAGE_METADATA),
    };
    const readings: Record<string, unknown>[] = [];
    const seen: unknown[] = [];
    for (const r of rumors) {
      const wrap = await chatWrap(r, ch, r.kind === kinds.KIND_TYPING);
      if (r.kind === kinds.KIND_TYPING) {
        // The typing reader (useTyping.ts): opens the ephemeral wrap at the current address,
        // checks the kind, the binding and the signal's liveness.
        let live = false;
        let ms: number | null = null;
        try {
          const o = stream.openWrap(wrap, ch.current.group as never);
          ms = o.ms;
          if (o.kind === kinds.KIND_TYPING) {
            stream.checkChannelBinding(o, GENERAL, 0n);
            live = isPlausiblyLive(o.ms);
          }
        } catch {
          live = false;
        }
        readings.push({ accepted: live, ms, typing: live });
        continue;
      }
      chat.clearChatDecodeMemo();
      const opened = (await chat.openChatBatch([wrap as never], ch as never))[0];
      if (opened) seen.push(opened);
      const folded2 = chat.foldTimeline(seen as never, moderation as never, { self: "f".repeat(64) });
      readings.push(readingOf(r, opened, folded2, seen as never));
    }
    return readings;
  }

  function pTags(tags: string[][]) {
    return tags.filter((t) => t[0] === "p" && t[1]).map((t) => t[1]);
  }

  function readingOf(r: Rumor, opened: Record<string, unknown> | undefined, folded: ReturnType<typeof chat.foldTimeline>, seen: Array<{ rumorId: string; author: string; kind: number }>) {
    const reading: Record<string, unknown> = { accepted: Boolean(opened), ms: opened ? (opened.ms as number) : null };
    if (!opened) return reading;
    const ev = { kind: r.kind, content: r.content, tags: r.tags };
    const visible = (rid: string) => folded.messages.find((m) => m.rumorId === rid);
    if (r.kind === kinds.KIND_MESSAGE || r.kind === kinds.KIND_COMMENT || r.kind === kinds.KIND_POLL) {
      reading.inline_reply_to = r.kind === kinds.KIND_MESSAGE ? (inlineReplyQuoteId(ev) ?? null) : null;
      reading.thread_root = chat.replyTargetOf(ev) ?? null;
      reading.thread_parent = r.kind === kinds.KIND_COMMENT ? (chat.eTargetOf(ev) ?? null) : null;
      reading.mentions = pTags(r.tags);
      reading.visible = Boolean(visible(r.id));
    }
    if (r.kind === kinds.KIND_EDIT) {
      const target = chat.eTargetOf(ev) ?? null;
      reading.target = target;
      const t = target ? visible(target) : undefined;
      reading.after = { target_content: t ? t.content : null, target_edited: Boolean(t?.editedMs) };
    }
    if (r.kind === kinds.KIND_REACTION) {
      const target = chat.eTargetOf(ev) ?? null;
      reading.target = target;
      const code = /^:(.+):$/.exec(r.content)?.[1];
      reading.reaction = {
        content: r.content,
        key: reactionContentKey(r.content),
        custom_emoji_url: code === undefined ? null : (r.tags.find((t) => t[0] === "emoji" && t[1] === code)?.[2] ?? null),
        renderable: isRenderableReaction(r.content),
      };
      const entry = target ? folded.reactions.get(target)?.get(reactionContentKey(r.content)) : undefined;
      reading.after = { tallied: Boolean(entry?.reactors.has(r.pubkey)), url: entry?.url ?? null };
    }
    if (r.kind === kinds.KIND_DELETE) {
      const deletes = r.tags.filter((t) => t[0] === "e" && t[1]).map((t) => t[1]);
      reading.deletes = deletes;
      reading.citation = Boolean(edition.citationFromTags(r.tags));
      const after: Record<string, unknown> = {};
      for (const d of deletes) {
        const target = seen.find((s) => s.rumorId === d);
        if (!target) {
          after[d] = "unknown";
        } else if (target.kind === kinds.KIND_REACTION) {
          let tallied = false;
          for (const byEmoji of folded.reactions.values()) for (const e of byEmoji.values()) if (e.reactors.get(target.author) === d) tallied = true;
          after[d] = tallied ? "kept" : "removed";
        } else {
          after[d] = visible(d) ? "kept" : "removed";
        }
      }
      reading.after = after;
    }
    return reading;
  }

  /** Armada's Guestbook reading of `rumors` under `folded`: the coalesced state of each member. */
  async function readGuestbook(rumors: Rumor[], folded: unknown) {
    const readings: Record<string, unknown>[] = [];
    const opened = [];
    for (const r of rumors) {
      const wrap = await gb.sealGuestbook(r as never, guestbookGroup, signerOf(r.pubkey));
      const o = gb.openGuestbookWraps([wrap], gb.guestbookGroups(community as never))[0];
      if (o) opened.push(o);
      const coalesced = gb.coalesceGuestbook(opened, guestbookFoldOptions(community as never, folded as never));
      const subject = r.kind === kinds.KIND_KICK ? r.tags.find((t) => t[0] === "p")?.[1] : r.pubkey;
      const member = subject ? coalesced.get(subject) : undefined;
      readings.push({
        accepted: Boolean(o),
        ms: o ? o.ms : null,
        member: subject ?? null,
        state: member && member.rumorId === r.id ? member.state : null,
        invite_creator: member && member.rumorId === r.id ? (member.invite?.creator ?? null) : null,
        invite_label: member && member.rumorId === r.id ? (member.invite?.label ?? null) : null,
      });
    }
    return readings;
  }

  /** Armada's Control reading of one more edition on top of `base`. */
  async function readEdition(base: Rumor[], r: Rumor) {
    const { folded, parsed } = await foldOf([...base, r]);
    const p = parsed.find((e) => bytesToHex(e.rumorId) === r.id);
    const f = folded as unknown as { roster: never; heads: Map<string, { version: bigint; hash: Uint8Array }> };
    const head = p ? f.heads.get(bytesToHex(p.entityId)) : undefined;
    const reading: Record<string, unknown> = { accepted: Boolean(p), honored: Boolean(head && p && bytesToHex(head.hash) === bytesToHex(p.selfHash)) };
    if (p?.vsk === kinds.VSK_ROLE) {
      const role = roles.roleFromJSON(r.content);
      const held = role ? roles.roleById(f.roster, role.roleId) : undefined;
      reading.role = held ? { role_id: held.roleId, position: held.position, permissions: held.permissions.toString() } : null;
    }
    if (p?.vsk === kinds.VSK_GRANT) {
      const grant = JSON.parse(r.content) as { member: string };
      reading.member_roles = roles.rolesOf(f.roster, grant.member).map((x) => x.roleId).sort();
    }
    return reading;
  }

  // ── THEIRS: what Armada writes ─────────────────────────────────────────────

  const ownerOpts = (version: bigint, secs: number) => ({ actorPubkey: PK.owner, version, createdAtSecs: T0 + secs });
  const theirControl: Rumor[] = [
    control.buildMetadataEdition(id, { name: "Vectors", relays: [RELAY] }, ownerOpts(1n, 0)),
    control.buildChannelEdition(hexToBytes(GENERAL), { name: "general", private: false }, ownerOpts(1n, 0)),
    control.buildRoleEdition(roles.adminRole(ADMIN_ROLE), ownerOpts(1n, 1)),
    control.buildRoleEdition(roles.moderatorRole(MOD_ROLE), ownerOpts(1n, 1)),
    control.buildGrantEdition(id, { member: PK.admin, roleIds: [ADMIN_ROLE] }, ownerOpts(1n, 2)),
    control.buildGrantEdition(id, { member: PK.mod, roleIds: [MOD_ROLE] }, ownerOpts(1n, 2)),
  ].map(rumorOf);
  const theirFold = (await foldOf(theirControl)).folded;
  h.folded = theirFold;
  const theirChannel = channelOf(theirFold);

  /** Sends through Armada's own `useSendMessage` as `who`, returns the rumor it sealed. */
  async function send(who: string, args: Record<string, unknown>): Promise<Rumor> {
    h.user = userOf(who);
    h.relays.clear();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const hook = renderHook(() => useSendMessage(community as never, theirChannel as never), { wrapper });
    // Past Armada's send budget: a retry's flag, which changes nothing in the rumor.
    const res = (await hook.result.current.mutateAsync({ ...args, bypassRateLimit: true } as never)) as { wrap?: NostrEvent };
    hook.unmount();
    if (!res.wrap) throw new Error("Armada sealed nothing");
    const opened = stream.openWrap(res.wrap, theirChannel.current.group as never);
    return rumorOf({ ...opened, id: opened.rumorId, pubkey: opened.author, created_at: opened.createdAt } as never);
  }

  const at = (secs: number, ms: number) => (T0 + secs) * 1000 + ms;
  const npubBob = nip19.npubEncode(PK.bob);
  const c: Record<string, Rumor> = {};
  c.message = await send("alice", { content: "hello from armada", ms: at(10, 101) });
  c.mention = await send("alice", { content: `hey nostr:${npubBob}`, extraTags: [["p", PK.bob]], ms: at(11, 202) });
  // The composer's tags for an inline reply on a sealed plane (ChatComposer buildMessageTags), kept by ConcordPage's handleSend.
  c.inline_reply = await send("bob", { content: "replying inline", extraTags: [["q", c.message.id, "", PK.alice], ["p", PK.alice]], ms: at(12, 303) });
  c.thread_reply = await send("bob", { content: "in the thread", replyTo: { id: c.message.id, kind: 9, pubkey: PK.alice, tags: c.message.tags }, ms: at(13, 404) });
  c.thread_reply_nested = await send("alice", { content: "deeper", replyTo: { id: c.thread_reply.id, kind: 1111, pubkey: PK.bob, tags: c.thread_reply.tags }, ms: at(14, 505) });
  // useTransport.editMessage: kind 3302 at the original, keeping its (absent) deadline.
  c.edit = await send("alice", { content: "hello from armada, edited", kind: 3302, target: c.message.id, targetKind: 9, expiration: null, ms: at(15, 606) });
  c.edit_by_other = await send("bob", { content: "not your message", kind: 3302, target: c.message.id, targetKind: 9, expiration: null, ms: at(16, 707) });
  // useTransport's react(): kind 7 with the target's kind and author, NIP-30 tags for a custom emoji.
  c.reaction_plus = await send("bob", { content: "+", kind: 7, target: c.message.id, targetKind: 9, targetPubkey: PK.alice, extraTags: customEmojiReactionTags("+"), ms: at(17, 808) });
  c.reaction_emoji = await send("bob", { content: "\u{1F919}", kind: 7, target: c.message.id, targetKind: 9, targetPubkey: PK.alice, extraTags: customEmojiReactionTags("\u{1F919}"), ms: at(18, 909) });
  c.reaction_custom = await send("bob", {
    content: ":soapbox:",
    kind: 7,
    target: c.message.id,
    targetKind: 9,
    targetPubkey: PK.alice,
    extraTags: customEmojiReactionTags(":soapbox:", "https://example.invalid/soapbox.png"),
    ms: at(19, 11),
  });
  // Removing one's own reaction: a kind 5 naming it with `k` 7.
  c.unreact = await send("bob", { content: "", kind: 5, target: c.reaction_plus.id, targetKind: 7, ms: at(20, 22) });
  // useMessageActions.deleteMessage: self-delete, no citation.
  c.delete_own = await send("alice", { content: "", kind: 5, target: c.mention.id, targetKind: 9, expiration: null, ms: at(21, 33) });
  // A moderation hide: deleteMessage on someone else's message cites the deleter's Grant (citationFor).
  const modCitation = citationFor(community as never, theirFold as never, PK.mod);
  c.moderator_hide = await send("mod", { content: "", kind: 5, target: c.inline_reply.id, targetKind: 9, extraTags: modCitation ? [edition.citationToTag(modCitation)] : undefined, ms: at(22, 44) });
  c.delete_by_non_moderator = await send("bob", { content: "", kind: 5, target: c.thread_reply_nested.id, targetKind: 1111, ms: at(23, 55) });
  c.poll = await send("alice", { content: "Lunch?", kind: 1068, extraTags: buildPollTags("Lunch?", [{ id: "a", label: "Yes" }, { id: "b", label: "No" }] as never, "singlechoice" as never, 0), ms: at(24, 66) });

  // useTypingPublisher: kind 23311 on an ephemeral wrap at the channel's current address.
  h.user = userOf("bob");
  h.relays.clear();
  {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const hook = renderHook(() => useTypingPublisher(community as never, theirChannel as never), { wrapper });
    hook.result.current();
    for (let i = 0; i < 100 && relayAt(RELAY).stored.length === 0; i++) await new Promise((r) => setTimeout(r, 20));
    hook.unmount();
    const wrap = relayAt(RELAY).stored.find((e) => e.kind === 21059);
    if (!wrap) throw new Error("no typing wrap");
    const o = stream.openWrap(wrap, theirChannel.current.group as never);
    c.typing = rumorOf({ ...o, id: o.rumorId, pubkey: o.author, created_at: o.createdAt } as never);
  }

  const chatOrder = Object.keys(c);
  const chatReadings = await readChat(chatOrder.map((k) => c[k]), theirFold);
  const theirCases: Record<string, unknown>[] = chatOrder.map((k, i) => ({ name: k, plane: "chat", rumor: c[k], expect: chatReadings[i] }));

  // Guestbook: Armada's builders, judged under the Roster above (mod holds KICK, admin holds KICK and BAN).
  const gbRumors: Array<[string, Rumor]> = [
    ["join", rumorOf(gb.buildJoinRumor(PK.alice, at(30, 1)))],
    ["join_with_invite", rumorOf(gb.buildJoinRumor(PK.bob, at(31, 2), { creator: PK.admin }))],
    ["join_with_labelled_invite", rumorOf(gb.buildJoinRumor(PK.mod, at(31, 3), { creator: PK.admin, label: "Reddit" }))],
    ["leave", rumorOf(gb.buildLeaveRumor(PK.alice, at(32, 4)))],
    ["kick_by_moderator", rumorOf(gb.buildKickRumor(PK.mod, PK.bob, at(33, 5), toVac(citationFor(community as never, theirFold as never, PK.mod))))],
    ["kick_by_member", rumorOf(gb.buildKickRumor(PK.alice, PK.mod, at(34, 6)))],
  ];
  const gbReadings = await readGuestbook(gbRumors.map(([, r]) => r), theirFold);
  gbRumors.forEach(([name, r], i) => theirCases.push({ name: `guestbook_${name}`, plane: "guestbook", rumor: r, expect: gbReadings[i] }));

  function toVac(cite: { entityId: Uint8Array; version: bigint; editionHash: Uint8Array } | undefined) {
    return cite ? { eid: bytesToHex(cite.entityId), version: cite.version, hash: bytesToHex(cite.editionHash) } : undefined;
  }

  // Control: role editions and grants by the admin (MANAGE_ROLES at position 1) and the moderator.
  const adminCite = citationFor(community as never, theirFold as never, PK.admin);
  const modCite = citationFor(community as never, theirFold as never, PK.mod);
  const helper = (perms: bigint, position: number) => ({ roleId: "a3".repeat(32), name: "Helper", position, permissions: perms, scope: { kind: "server" as const }, color: 0 });
  const editions: Array<[string, Rumor]> = [
    ["role_subset_of_authors_bits", rumorOf(control.buildRoleEdition(helper(roles.Permissions.KICK, 3), { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 40, authority: adminCite }))],
    ["role_with_a_bit_the_author_lacks", rumorOf(control.buildRoleEdition(helper(roles.Permissions.MODERATE_MEMBERS, 3), { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 41, authority: adminCite }))],
    ["role_at_the_authors_own_rank", rumorOf(control.buildRoleEdition(helper(roles.Permissions.KICK, 1), { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 42, authority: adminCite }))],
    ["role_by_member_without_manage_roles", rumorOf(control.buildRoleEdition(helper(roles.Permissions.KICK, 3), { actorPubkey: PK.mod, version: 1n, createdAtSecs: T0 + 43, authority: modCite }))],
    ["role_uncited", rumorOf(control.buildRoleEdition(helper(roles.Permissions.KICK, 3), { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 47 }))],
    ["grant_by_admin", rumorOf(control.buildGrantEdition(id, { member: PK.bob, roleIds: [MOD_ROLE] }, { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 44, authority: adminCite }))],
    ["grant_by_admin_uncited", rumorOf(control.buildGrantEdition(id, { member: PK.bob, roleIds: [MOD_ROLE] }, { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 45 }))],
    ["grant_by_moderator", rumorOf(control.buildGrantEdition(id, { member: PK.bob, roleIds: [MOD_ROLE] }, { actorPubkey: PK.mod, version: 1n, createdAtSecs: T0 + 46, authority: modCite }))],
    ["grant_strip_uncited", rumorOf(control.buildGrantEdition(id, { member: PK.mod, roleIds: [] }, { actorPubkey: PK.admin, version: 1n, createdAtSecs: T0 + 48 }))],
  ];
  for (const [name, r] of editions) theirCases.push({ name: `control_${name}`, plane: "control", rumor: r, expect: await readEdition(theirControl, r) });

  // Channel order: Armada's `armada.order` position (channelOrder.ts), then name.
  const orderEditions = [
    rumorOf(control.buildChannelEdition(hexToBytes("c1".repeat(32)), withChannelPosition({ name: "zeta", private: false }, 0), ownerOpts(1n, 50))),
    rumorOf(control.buildChannelEdition(hexToBytes("c2".repeat(32)), withChannelPosition({ name: "alpha", private: false }, 1), ownerOpts(1n, 50))),
    rumorOf(control.buildChannelEdition(hexToBytes("c3".repeat(32)), { name: "beta", private: false }, ownerOpts(1n, 50))),
  ];
  const ordered = channelsView(community as never, (await foldOf([...theirControl, ...orderEditions])).folded as never).map((x) => x.idHex);
  theirCases.push({ name: "channel_order", plane: "control", rumors: orderEditions, expect: { order: ordered } });

  const source = `Armada ${process.env.ARMADA_REV ?? "unknown"} (soapbox.pub) via quartz/tools/concord-vector-gen`;
  writeFileSync(
    `${out}/armada.json`,
    JSON.stringify({ source, community: communityJson, actors: PK, control: theirControl, cases: theirCases }, null, 2) + "\n",
  );

  // ── OURS: what Armada makes of Quartz's rumors ─────────────────────────────

  if (process.env.OURS_IN) {
    const ours = JSON.parse(readFileSync(process.env.OURS_IN, "utf8")) as { control: Rumor[]; cases: Array<{ name: string; plane: string; rumor?: Rumor; rumors?: Rumor[] }> };
    if (ours.control.some((r) => !NAME_OF.has(r.pubkey))) throw new Error("ours.rumors.json was built with other keys");
    const ourFold = (await foldOf(ours.control)).folded;
    const out2: Record<string, unknown>[] = [];
    const chatCases = ours.cases.filter((x) => x.plane === "chat");
    const chatR = await readChat(chatCases.map((x) => x.rumor!), ourFold);
    chatCases.forEach((x, i) => out2.push({ ...x, armada: chatR[i] }));
    const gbCases = ours.cases.filter((x) => x.plane === "guestbook");
    const gbR = await readGuestbook(gbCases.map((x) => x.rumor!), ourFold);
    gbCases.forEach((x, i) => out2.push({ ...x, armada: gbR[i] }));
    for (const x of ours.cases.filter((y) => y.plane === "control")) {
      if (x.rumors) {
        const order = channelsView(community as never, (await foldOf([...ours.control, ...x.rumors])).folded as never).map((y) => y.idHex);
        out2.push({ ...x, armada: { order } });
      } else {
        out2.push({ ...x, armada: await readEdition(ours.control, x.rumor!) });
      }
    }
    writeFileSync(
      `${out}/ours-read-by-armada.json`,
      JSON.stringify({ source, community: communityJson, actors: PK, control: ours.control, cases: out2 }, null, 2) + "\n",
    );
  }

  expect(theirCases.length).toBeGreaterThan(0);
});
