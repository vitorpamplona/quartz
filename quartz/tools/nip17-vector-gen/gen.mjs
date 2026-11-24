// Regenerates quartz/src/commonTest/resources/nip17/ with nostr-tools (Unlicense), offline.
//
//   OURS_IN=dump.json VECTORS_OUT=dir node gen.mjs
//
// nostr-tools.json (THEIRS -> US): wraps nostr-tools makes from the fixture's keys (alice 44…44 to
// bob 55…55 and carol 66…66): kind 14 through nip17.wrapEvent / wrapManyEvents; kind 7 and kind 15,
// which nostr-tools has no NIP-17 helper for, as NIP-25 / NIP-17 "File Message" rumors through
// nip59.wrapEvent; the two wraps of NIP-17's own example; and an impersonation attempt (a rumor
// claiming alice inside a seal mallory 77…77 signed). Each with `expect`, the rumor read in the
// vocabulary Nip17VectorTest uses.
//
// ours-read-by-nostr-tools.json (OURS -> THEM): every wrap in OURS_IN (Nip17OursDump) opened with
// nostr-tools' nip59.unwrapEvent and the recipient's key, with what it found (or its error).
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { finalizeEvent, getPublicKey, getEventHash } from 'nostr-tools/pure'
import * as nip17 from 'nostr-tools/nip17'
import * as nip59 from 'nostr-tools/nip59'
import * as nip44 from 'nostr-tools/nip44'
import * as nip19 from 'nostr-tools/nip19'

const hex = (h) => Uint8Array.from(Buffer.from(h, 'hex'))
const toHex = (b) => Buffer.from(b).toString('hex')
const SK = { alice: '44'.repeat(32), bob: '55'.repeat(32), carol: '66'.repeat(32), mallory: '77'.repeat(32) }
const PK = Object.fromEntries(Object.entries(SK).map(([k, v]) => [k, getPublicKey(hex(v))]))
const T0 = 1767225600
const version = JSON.parse(readFileSync(new URL('./node_modules/nostr-tools/package.json', import.meta.url))).version

/** A rumor in Nip17VectorTest's vocabulary, from its raw tags. */
function read(r) {
  const tagged = (k) => r.tags.filter((t) => t[0] === k).map((t) => t[1])
  const first = (k) => tagged(k)[0] ?? null
  const out = { id: r.id, kind: r.kind, pubkey: r.pubkey, content: r.content }
  if (r.kind === 14) {
    Object.assign(out, { recipients: tagged('p').sort(), reply_to: tagged('e'), subject: first('subject') })
  } else if (r.kind === 15) {
    Object.assign(out, {
      recipients: tagged('p').sort(),
      reply_to: tagged('e'),
      file: { url: r.content, type: first('file-type'), algorithm: first('encryption-algorithm'), key: first('decryption-key'), nonce: first('decryption-nonce'), x: first('x') },
    })
  } else if (r.kind === 7) {
    Object.assign(out, { recipients: tagged('p').sort(), target: tagged('e') })
  } else if (r.kind === 5) {
    out.deletes = tagged('e')
  }
  return out
}

const theirs = []
function add(name, recipient, wrap, extra = {}) {
  const sk = extra.recipient_sk ?? SK[recipient]
  theirs.push({ name, recipient, ...extra, wrap, expect: read(nip59.unwrapEvent(wrap, hex(sk))) })
}

// kind 14 through nostr-tools' NIP-17 helpers
add('text', 'bob', nip17.wrapEvent(hex(SK.alice), { publicKey: PK.bob }, 'hello bob, from nostr-tools', undefined, undefined, T0))
const text = nip59.unwrapEvent(theirs[0].wrap, hex(SK.bob))
const replyWraps = nip17.wrapManyEvents(hex(SK.alice), [{ publicKey: PK.bob }], 'nostr-tools replies', 'Pizza night', { eventId: text.id }, T0 + 1)
add('reply_with_subject', 'bob', replyWraps[1])
add('reply_sender_copy', 'alice', replyWraps[0])
const group = nip17.wrapManyEvents(hex(SK.alice), [{ publicKey: PK.bob }, { publicKey: PK.carol }], 'group hello from nostr-tools', undefined, undefined, T0 + 2)
add('group_to_bob', 'bob', group[1])
add('group_to_carol', 'carol', group[2])
const groupRumor = nip59.unwrapEvent(group[2], hex(SK.carol))

// kind 7 and kind 15: rumors by NIP-25 / NIP-17, wrapped with nip59
add('reaction_in_group', 'carol', nip59.wrapEvent({ kind: 7, created_at: T0 + 3, content: '🍕', tags: [['e', groupRumor.id], ['p', PK.alice], ['k', '14']] }, hex(SK.bob), PK.carol))
const x = 'ab'.repeat(32)
add('file', 'bob', nip59.wrapEvent({
  kind: 15,
  created_at: T0 + 4,
  content: `https://blossom.example/${x}`,
  tags: [['p', PK.bob], ['file-type', 'image/png'], ['encryption-algorithm', 'aes-gcm'], ['decryption-key', '11'.repeat(32)], ['decryption-nonce', '22'.repeat(12)], ['x', x], ['size', '6']],
}, hex(SK.alice), PK.bob))

// NIP-17's own example: "Hola, que tal?", the wrap to the receiver
const spec = JSON.parse(readFileSync(new URL('./nip17-example.json', import.meta.url)))
const receiverSk = toHex(nip19.decode(spec.receiver_nsec).data)
add('nip17_spec_example', 'receiver', spec.wraps[0], { recipient_sk: receiverSk })

// impersonation: mallory seals a rumor that claims to be alice's
{
  const rumor = { kind: 14, created_at: T0 + 5, content: 'I am alice', tags: [['p', PK.bob]], pubkey: PK.alice }
  rumor.id = getEventHash(rumor)
  const seal = finalizeEvent({ kind: 13, created_at: T0 + 5, tags: [], content: nip44.encrypt(JSON.stringify(rumor), nip44.getConversationKey(hex(SK.mallory), PK.bob)) }, hex(SK.mallory))
  const wrap = nip59.createWrap(seal, PK.bob)
  let error = null
  try { nip59.unwrapEvent(wrap, hex(SK.bob)) } catch (e) { error = String(e.message || e) }
  theirs.push({ name: 'impersonation', recipient: 'bob', wrap, expect: { nostr_tools_error: error, claimed_pubkey: PK.alice, seal_pubkey: PK.mallory } })
}

const oursIn = JSON.parse(readFileSync(process.env.OURS_IN, 'utf8'))
const ours = oursIn.cases.map((c) => {
  let reading
  try {
    reading = read(nip59.unwrapEvent(c.wrap, hex(SK[c.recipient])))
  } catch (e) {
    reading = { error: String(e.message || e) }
  }
  return { name: c.name, recipient: c.recipient, rumor: c.rumor, wrap: c.wrap, nostr_tools: reading }
})

const source = `nostr-tools ${version}`
writeFileSync(join(process.env.VECTORS_OUT, 'nostr-tools.json'), JSON.stringify({ source, users: PK, cases: theirs }, null, 2) + '\n')
writeFileSync(join(process.env.VECTORS_OUT, 'ours-read-by-nostr-tools.json'), JSON.stringify({ source, users: PK, cases: ours }, null, 2) + '\n')
console.log(`wrote ${theirs.length} theirs and ${ours.length} ours cases`)
