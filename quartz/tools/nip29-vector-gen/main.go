// nip29-vector-gen regenerates quartz/src/commonTest/resources/nip29/ against fiatjaf/relay29
// (MIT), the NIP-29 relay library by the NIP's author, and go-nostr's nip29 package it uses.
//
//	go run . -out DIR [-ours FILE]
//
// THEIRS -> US (relay29.json): runs khatru29 in this process on a loopback port, drives one group
// through create, edit, joins, a role, a removal, a leave and the private/closed flags with fixed
// user keys, and records every relay-signed event relay29 emits (the 39000-39003 snapshots after
// each step and the 9000/9001 it issues for 9021/9022), each with `expect`: what go-nostr's nip29
// reader and relay29's moderation parser make of it.
//
// OURS -> THEM (ours-read-by-relay29.json): reads the events Quartz's builders write (FILE, dumped by
// Nip29OursDump) and records what relay29 makes of each: its moderation action, and for an edit
// the group it leaves when applied to a private, closed baseline group.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/fiatjaf/eventstore/slicestore"
	"github.com/fiatjaf/relay29"
	"github.com/fiatjaf/relay29/khatru29"
	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip29"
)

const (
	relaySK = "9999999999999999999999999999999999999999999999999999999999999999"
	groupID = "interop-pizza"
)

var sk = map[string]string{
	"owner": strings.Repeat("11", 32),
	"alice": strings.Repeat("44", 32),
	"bob":   strings.Repeat("55", 32),
	"carol": strings.Repeat("66", 32),
}

func pk(name string) string { p, _ := nostr.GetPublicKey(sk[name]); return p }

var (
	adminRole     = &nip29.Role{Name: "admin", Description: "the group's max top admin"}
	moderatorRole = &nip29.Role{Name: "moderator", Description: "the person who cleans up unwanted stuff"}
)

type obj = map[string]any

// ---- readings (go-nostr / relay29 vocabulary) -----------------------------------------------

func readState(evt *nostr.Event) obj {
	g := nip29.Group{Address: nip29.GroupAddress{ID: evt.Tags.GetD()}, Name: evt.Tags.GetD(), Members: map[string][]*nip29.Role{}, Roles: []*nip29.Role{adminRole, moderatorRole}}
	switch evt.Kind {
	case nostr.KindSimpleGroupMetadata:
		if err := g.MergeInMetadataEvent(evt); err != nil {
			return obj{"error": err.Error()}
		}
		return obj{"group_id": g.Address.ID, "name": g.Name, "about": g.About, "picture": g.Picture, "private": g.Private, "closed": g.Closed}
	case nostr.KindSimpleGroupAdmins:
		if err := g.MergeInAdminsEvent(evt); err != nil {
			return obj{"error": err.Error()}
		}
		admins := obj{}
		for member, roles := range g.Members {
			names := []string{}
			for _, r := range roles {
				names = append(names, r.Name)
			}
			admins[member] = names
		}
		return obj{"group_id": g.Address.ID, "admins": admins}
	case nostr.KindSimpleGroupMembers:
		if err := g.MergeInMembersEvent(evt); err != nil {
			return obj{"error": err.Error()}
		}
		members := []string{}
		for m := range g.Members {
			members = append(members, m)
		}
		sort.Strings(members)
		return obj{"group_id": g.Address.ID, "members": members}
	case nostr.KindSimpleGroupRoles:
		roles := []obj{}
		for _, tag := range evt.Tags.GetAll([]string{"role", ""}) {
			desc := ""
			if len(tag) > 2 {
				desc = tag[2]
			}
			roles = append(roles, obj{"name": tag[1], "description": desc})
		}
		return obj{"group_id": evt.Tags.GetD(), "roles": roles}
	}
	return readAction(evt)
}

// readAction is relay29's moderation parser (PrepareModerationAction), plus what a join/leave/chat
// event is to it.
func readAction(evt *nostr.Event) obj {
	h := ""
	if t := evt.Tags.GetFirst([]string{"h", ""}); t != nil {
		h = (*t)[1]
	}
	switch evt.Kind {
	case nostr.KindSimpleGroupJoinRequest:
		code := ""
		if t := evt.Tags.GetFirst([]string{"code", ""}); t != nil {
			code = (*t)[1]
		}
		return obj{"group_id": h, "action": "join-request", "code_read": false, "code": code}
	case nostr.KindSimpleGroupLeaveRequest:
		return obj{"group_id": h, "action": "leave-request"}
	}
	if !nip29.ModerationEventKinds.Includes(evt.Kind) {
		return obj{"group_id": h, "action": "message", "kind": evt.Kind}
	}
	action, err := relay29.PrepareModerationAction(evt)
	if err != nil {
		return obj{"group_id": h, "error": err.Error()}
	}
	out := obj{"group_id": h, "action": action.Name()}
	switch a := action.(type) {
	case relay29.PutUser:
		targets := []obj{}
		for _, t := range a.Targets {
			roles := t.RoleNames
			if roles == nil {
				roles = []string{}
			}
			targets = append(targets, obj{"pubkey": t.PubKey, "roles": roles})
		}
		out["targets"] = targets
	case relay29.RemoveUser:
		out["targets"] = a.Targets
	case relay29.DeleteEvent:
		out["targets"] = a.Targets
	case relay29.EditMetadata:
		// Applied to the baseline: a named, private, closed group.
		g := nip29.Group{Name: "Baseline", About: "baseline about", Picture: "https://example.com/b.png", Private: true, Closed: true, Members: map[string][]*nip29.Role{}}
		a.Apply(&g)
		out["applied_to_private_closed"] = obj{"name": g.Name, "about": g.About, "picture": g.Picture, "private": g.Private, "closed": g.Closed}
	}
	return out
}

// ---- driving relay29 ------------------------------------------------------------------------

type gen struct {
	ctx   context.Context
	url   string
	conns map[string]*nostr.Relay
	cases []obj
	seen  map[string]bool
}

func (g *gen) conn(who string) *nostr.Relay {
	if r, ok := g.conns[who]; ok {
		return r
	}
	r, err := nostr.RelayConnect(g.ctx, g.url)
	if err != nil {
		log.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond) // the AUTH challenge khatru sends on connect
	if err := r.Auth(g.ctx, func(e *nostr.Event) error { return e.Sign(sk[who]) }); err != nil {
		log.Fatalf("auth %s: %v", who, err)
	}
	g.conns[who] = r
	return r
}

func (g *gen) send(who string, kind int, tags nostr.Tags) (*nostr.Event, error) {
	evt := nostr.Event{Kind: kind, CreatedAt: nostr.Now(), Tags: append(nostr.Tags{{"h", groupID}}, tags...)}
	if err := evt.Sign(sk[who]); err != nil {
		log.Fatal(err)
	}
	err := g.conn(who).Publish(g.ctx, evt)
	time.Sleep(300 * time.Millisecond)
	return &evt, err
}

func (g *gen) must(who string, kind int, tags nostr.Tags) *nostr.Event {
	evt, err := g.send(who, kind, tags)
	if err != nil {
		log.Fatalf("%s kind %d refused: %v", who, kind, err)
	}
	return evt
}

func eventJSON(evt *nostr.Event) obj {
	var o obj
	b, _ := json.Marshal(evt)
	_ = json.Unmarshal(b, &o)
	return o
}

// snapshot records the relay-signed state events relay29 serves now.
func (g *gen) snapshot(step string, kinds ...int) {
	for _, k := range kinds {
		evts, err := g.conn("owner").QuerySync(g.ctx, nostr.Filter{Kinds: []int{k}, Tags: nostr.TagMap{"d": {groupID}}})
		if err != nil || len(evts) == 0 {
			log.Fatalf("snapshot %s kind %d: %v (%d)", step, k, err, len(evts))
		}
		evt := evts[0]
		g.cases = append(g.cases, obj{"name": fmt.Sprintf("%s.%d", step, k), "event": eventJSON(evt), "expect": readState(evt)})
	}
}

// relayIssued records the 9000/9001 relay29 signed itself since the last call.
func (g *gen) relayIssued(step string) {
	relayPK, _ := nostr.GetPublicKey(relaySK)
	evts, err := g.conn("owner").QuerySync(g.ctx, nostr.Filter{Kinds: []int{9000, 9001}, Tags: nostr.TagMap{"h": {groupID}}})
	if err != nil {
		log.Fatal(err)
	}
	sort.Slice(evts, func(i, j int) bool { return evts[i].CreatedAt < evts[j].CreatedAt || (evts[i].CreatedAt == evts[j].CreatedAt && evts[i].ID < evts[j].ID) })
	for _, evt := range evts {
		if evt.PubKey != relayPK || g.seen[evt.ID] {
			continue
		}
		g.seen[evt.ID] = true
		g.cases = append(g.cases, obj{"name": fmt.Sprintf("%s.%d", step, evt.Kind), "event": eventJSON(evt), "expect": readState(evt)})
	}
}

func theirs() obj {
	db := &slicestore.SliceStore{}
	db.Init()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	domain := ln.Addr().String()
	relay, state := khatru29.Init(relay29.Options{
		Domain: domain, DB: db, SecretKey: relaySK,
		DefaultRoles: []*nip29.Role{adminRole, moderatorRole}, GroupCreatorDefaultRole: adminRole,
	})
	relay.ServiceURL = "ws://" + domain
	state.AllowAction = func(ctx context.Context, group nip29.Group, role *nip29.Role, action relay29.Action) bool {
		if role == adminRole {
			return true
		}
		if role == moderatorRole {
			switch action.(type) {
			case relay29.RemoveUser, relay29.DeleteEvent:
				return true
			}
		}
		return false
	}
	go http.Serve(ln, relay)

	g := &gen{ctx: context.Background(), url: "ws://" + domain, conns: map[string]*nostr.Relay{}, seen: map[string]bool{}}
	all := []int{39000, 39001, 39002, 39003}

	g.must("owner", 9007, nil)
	g.snapshot("created", all...)
	time.Sleep(1100 * time.Millisecond)
	g.must("owner", 9002, nostr.Tags{{"name", "Pizza Lovers"}, {"about", "a group for people who love pizza"}, {"picture", "https://pizza.example/p.png"}})
	g.snapshot("named", 39000)
	for _, who := range []string{"alice", "bob", "carol"} {
		g.must(who, 9021, nil)
		g.relayIssued(who + "_joined")
	}
	g.snapshot("three_joined", 39002)
	time.Sleep(1100 * time.Millisecond)
	g.must("owner", 9000, nostr.Tags{{"p", pk("bob"), "moderator"}})
	g.snapshot("bob_moderator", 39001)
	time.Sleep(1100 * time.Millisecond)
	g.must("bob", 9001, nostr.Tags{{"p", pk("carol")}})
	g.snapshot("carol_removed", 39002)
	time.Sleep(1100 * time.Millisecond)
	g.must("alice", 9022, nil)
	g.relayIssued("alice_left")
	g.snapshot("alice_left", 39002)
	g.must("carol", 9021, nil) // removed users are not let back in: relay29 issues no 9000
	g.relayIssued("carol_rejoined")
	time.Sleep(1100 * time.Millisecond)
	g.must("owner", 9002, nostr.Tags{{"name", "Pizza Lovers"}, {"private"}, {"closed"}})
	g.snapshot("private_closed", 39000, 39001)
	time.Sleep(1100 * time.Millisecond)
	g.must("owner", 9002, nostr.Tags{{"name", "Pizza Lovers"}, {"public"}, {"open"}})
	g.snapshot("legacy_public_open", 39000)

	relayPK, _ := nostr.GetPublicKey(relaySK)
	users := obj{}
	for name := range sk {
		users[name] = pk(name)
	}
	return obj{
		"source":   "fiatjaf/relay29 " + relay29Version() + " (khatru29, go-nostr nip29)",
		"relay":    relayPK,
		"group_id": groupID,
		"users":    users,
		"cases":    g.cases,
	}
}

func relay29Version() string {
	if v := os.Getenv("RELAY29_VERSION"); v != "" {
		return v
	}
	return "unknown"
}

func ours(path string) obj {
	b, err := os.ReadFile(path)
	if err != nil {
		log.Fatal(err)
	}
	var dump struct {
		Cases []struct {
			Name  string       `json:"name"`
			Event *nostr.Event `json:"event"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(b, &dump); err != nil {
		log.Fatal(err)
	}
	cases := []obj{}
	for _, c := range dump.Cases {
		ok, _ := c.Event.CheckSignature()
		reading := readAction(c.Event)
		reading["signature_valid"] = ok
		cases = append(cases, obj{"name": c.Name, "event": eventJSON(c.Event), "relay29": reading})
	}
	return obj{"source": "fiatjaf/relay29 " + relay29Version() + " (PrepareModerationAction, go-nostr nip29)", "cases": cases}
}

func write(dir, name string, v obj) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		log.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, name), append(b, '\n'), 0o644); err != nil {
		log.Fatal(err)
	}
}

func main() {
	out := flag.String("out", "", "directory for relay29.json and ours-read-by-relay29.json")
	oursIn := flag.String("ours", "", "the dump of Quartz's events (Nip29OursDump)")
	flag.Parse()
	if *out == "" {
		log.Fatal("-out is required")
	}
	write(*out, "relay29.json", theirs())
	if *oursIn != "" {
		write(*out, "ours-read-by-relay29.json", ours(*oursIn))
	}
}
