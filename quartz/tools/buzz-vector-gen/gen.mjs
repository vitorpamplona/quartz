// Regenerates quartz/src/commonTest/resources/buzz/vectors/ against a running buzz-relay (see gen.sh).
//
//   RELAY=ws://127.0.0.1:7600 OURS_IN=dump.json VECTORS_OUT=dir BUZZ_REV=… node gen.mjs
//
// The relay's owner is the fixture's `owner` key (11…11) and its signing key is fixed, so its
// pubkey is stable. The script sends the templates Quartz's builders wrote (OURS_IN, from
// BuzzOursDump), signed here with the fixture's keys and the current time, in scenario order, and
// records each OK; around them it posts the messages a scenario needs (to pin, to reply to) and
// snapshots the relay-signed state Buzz emits. Output:
//   buzz.json                 THEIRS -> US: relay-signed events (39000/39001/39002, 30623 pins,
//                             39005 thread summaries) with `expect`, what the scenario set up.
//   ours-read-by-buzz.json    OURS -> THEM: each template with Buzz's OK (accepted, message) and,
//                             for a DM command, the `response:{…}` it carries, decoded as JSON.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { WebSocket } from 'ws'
import { finalizeEvent, getPublicKey } from 'nostr-tools/pure'
import { Relay, useWebSocketImplementation } from 'nostr-tools/relay'
import { getToken } from 'nostr-tools/nip98'

useWebSocketImplementation(WebSocket)

const RELAY = process.env.RELAY
const HTTP = RELAY.replace(/^ws/, 'http')
const hex = (h) => Uint8Array.from(Buffer.from(h, 'hex'))
const SK = { owner: '11'.repeat(32), alice: '44'.repeat(32), bob: '55'.repeat(32), carol: '66'.repeat(32) }
const PK = Object.fromEntries(Object.entries(SK).map(([k, v]) => [k, getPublicKey(hex(v))]))
const ours = JSON.parse(readFileSync(process.env.OURS_IN, 'utf8'))
const PLACEHOLDERS = ours.placeholders // name -> placeholder value the fixture wrote
const live = {} // placeholder name -> live value
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const conns = {}
async function conn(who) {
  if (conns[who]) return conns[who]
  const r = await Relay.connect(RELAY)
  r.onauth = async (t) => finalizeEvent(t, hex(SK[who]))
  await sleep(400)
  conns[who] = r
  return r
}

async function publish(who, tmpl) {
  const evt = finalizeEvent({ created_at: Math.floor(Date.now() / 1000), ...tmpl }, hex(SK[who]))
  const r = await conn(who)
  let ok, message
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      message = await r.publish(evt)
      ok = true
    } catch (e) {
      ok = false
      message = String(e && e.message ? e.message : e)
    }
    if (ok || !/auth-required/.test(message)) break
    await sleep(500)
  }
  await sleep(300)
  return { evt, ok, message }
}

async function query(who, filter) {
  const r = await conn(who)
  const out = []
  await new Promise((resolve) => {
    const sub = r.subscribe([filter], { onevent: (e) => out.push(e), oneose: () => { sub.close(); resolve() } })
    setTimeout(resolve, 5000)
  })
  return out
}

function substitute(tags) {
  return tags.map((t) => t.map((v) => {
    for (const [name, value] of Object.entries(PLACEHOLDERS)) if (v === value && live[name]) return live[name]
    return v
  }))
}

const theirs = []
const oursOut = []
const relaySelf = (await (await fetch(HTTP + '/', { headers: { Accept: 'application/nostr+json' } })).json())
const RELAY_PK = relaySelf.self || relaySelf.pubkey

function newest(events) {
  return events.filter((e) => e.pubkey === RELAY_PK).sort((a, b) => b.created_at - a.created_at)[0]
}

async function snapshot(name, kind, filter, expect) {
  const evt = newest(await query('owner', { kinds: [kind], ...filter }))
  if (!evt) throw new Error(`no relay-signed ${kind} for ${name}`)
  theirs.push({ name, event: evt, expect })
  return evt
}

function decodeResponse(message) {
  const i = (message || '').indexOf('response:')
  if (i < 0) return null
  try {
    return JSON.parse(message.slice(i + 'response:'.length).trim())
  } catch {
    return null
  }
}

async function sendOurs(name) {
  const c = ours.cases.find((x) => x.name === name)
  if (!c) throw new Error('no ours case ' + name)
  const tmpl = { kind: c.kind, tags: substitute(c.tags), content: c.content }
  const res = await publish(c.who, tmpl)
  const entry = { name, who: c.who, template: { kind: c.kind, tags: c.tags, content: c.content }, buzz: { accepted: res.ok, message: res.message } }
  const response = decodeResponse(res.message)
  if (response) entry.buzz.response = response
  oursOut.push(entry)
  return res
}

const CH1 = PLACEHOLDERS.stream_channel
const CH2 = PLACEHOLDERS.forum_channel

// --- channels -------------------------------------------------------------------------------
await sendOurs('create_stream')
await sendOurs('create_stream_edit')
await snapshot('stream_created.39000', 39000, { '#d': [CH1] }, { group_id: CH1, name: 'pizza-lovers', about: 'a channel for people who love pizza', private: false, channel_type: 'stream', listed: true, dm: false })
await snapshot('stream_created.39001', 39001, { '#d': [CH1] }, { group_id: CH1, admins: { [PK.owner]: ['owner'] } })
await sendOurs('put_user_member')
await sendOurs('put_user_admin')
await snapshot('roles_set.39001', 39001, { '#d': [CH1] }, { group_id: CH1, admins: { [PK.owner]: ['owner'], [PK.bob]: ['admin'] } })
await snapshot('roles_set.39002', 39002, { '#d': [CH1] }, { group_id: CH1, members: [PK.owner, PK.alice, PK.bob].sort() })

// --- pins -----------------------------------------------------------------------------------
const m1 = await publish('alice', { kind: 9, tags: [['h', CH1]], content: 'first message to pin' })
const m2 = await publish('alice', { kind: 9, tags: [['h', CH1]], content: 'second message to pin' })
live.message_one = m1.evt.id
live.message_two = m2.evt.id
await sendOurs('pin_first')
await sleep(1100)
await sendOurs('pin_second')
await snapshot('two_pinned.30623', 30623, { '#h': [CH1] }, { channel: CH1, pinned: [m2.evt.id, m1.evt.id] })
await sleep(1100)
await sendOurs('unpin_first')
await snapshot('one_unpinned.30623', 30623, { '#h': [CH1] }, { channel: CH1, pinned: [m2.evt.id] })

// --- listed names, a private forum --------------------------------------------------------
await sendOurs('create_duplicate_listed_name')
await sendOurs('create_private_forum')
await sendOurs('create_private_forum_edit')
await snapshot('forum_created.39000', 39000, { '#d': [CH2] }, { group_id: CH2, name: 'pizza-lovers', about: 'recipes', private: true, channel_type: 'forum', listed: false, dm: false })

// --- DMs ------------------------------------------------------------------------------------
const opened = await sendOurs('dm_open')
const dm = decodeResponse(opened.message)
if (!dm || !dm.channel_id) throw new Error('dm_open: no channel_id in ' + opened.message)
live.dm_channel = dm.channel_id
await snapshot('dm_opened.39000', 39000, { '#d': [dm.channel_id] }, { group_id: dm.channel_id, private: true, channel_type: 'dm', listed: false, dm: true })
await sendOurs('dm_add_with_history')
await snapshot('dm_grown.39002', 39002, { '#d': [dm.channel_id] }, { group_id: dm.channel_id, members: [PK.owner, PK.alice, PK.carol].sort() })
await sendOurs('dm_add_without_history')
await sendOurs('dm_open_again')

// --- thread summaries (bridge /query, NIP-98) -------------------------------------------------
const root = await publish('alice', { kind: 9, tags: [['h', CH1]], content: 'a thread root' })
await publish('bob', { kind: 9, tags: [['h', CH1], ['e', root.evt.id, '', 'reply']], content: 'first reply' })
await publish('owner', { kind: 9, tags: [['h', CH1], ['e', root.evt.id, '', 'reply']], content: 'second reply' })
const body = JSON.stringify([{ kinds: [9], '#h': [CH1], top_level: true, include_summaries: true, limit: 50 }])
const token = await getToken(HTTP + '/query', 'POST', (t) => finalizeEvent(t, hex(SK.owner)), true, JSON.parse(body))
const res = await fetch(HTTP + '/query', { method: 'POST', headers: { Authorization: token, 'Content-Type': 'application/json' }, body })
const window = await res.json()
const rows = Array.isArray(window) ? window : window.events || []
const summary = rows.find((e) => e.kind === 39005 && e.tags.some((t) => t[0] === 'd' && t[1] === root.evt.id))
if (!summary) throw new Error('no 39005 for the thread root: ' + JSON.stringify(window).slice(0, 400))
theirs.push({ name: 'thread.39005', event: summary, expect: { root: root.evt.id, channel: CH1, reply_count: 2, participants: [PK.bob, PK.owner].sort() } })

for (const r of Object.values(conns)) r.close()

const source = `block/buzz buzz-relay ${process.env.BUZZ_REV || 'unknown'}`
writeFileSync(join(process.env.VECTORS_OUT, 'buzz.json'), JSON.stringify({ source, relay: RELAY_PK, users: PK, cases: theirs }, null, 2) + '\n')
writeFileSync(join(process.env.VECTORS_OUT, 'ours-read-by-buzz.json'), JSON.stringify({ source, relay: RELAY_PK, users: PK, placeholders: PLACEHOLDERS, cases: oursOut }, null, 2) + '\n')
console.log(`wrote ${theirs.length} theirs and ${oursOut.length} ours cases`)
