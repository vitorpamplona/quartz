#!/usr/bin/env python3
# Lists event classes that drift from references/event-class-anatomy.md.
#
#   python3 .claude/skills/nostr-expert/scripts/event-pattern-audit.py            # every class
#   python3 .claude/skills/nostr-expert/scripts/event-pattern-audit.py nip88Polls # one package (path substring)
#
# Reads every class EventFactory.create builds. Heuristic: read the class before changing it.
import os
import re
import sys

ROOT = "quartz/src/commonMain/kotlin"
FACTORY = ROOT + "/com/vitorpamplona/quartz/utils/EventFactory.kt"
REFERENCE_PARSERS = r"(ETag|ATag|PTag|QTag|MarkedETag|EventBookmark|AddressBookmark)"
# Which provider each reference parser needs; a `q` may quote an event or an address.
PROVIDER_FOR = {"PTag": ("PubKey",), "ETag": ("Event",), "ATag": ("Address",), "QTag": ("Event", "Address")}


def missing_provider(src, parents):
    sources = [src] + parents
    for parser in set(re.findall(r"\b(PTag|ETag|ATag|QTag)::parse", src)):
        if not any(re.search(kind + "HintProvider", s) for kind in PROVIDER_FOR[parser] for s in sources):
            return True
    return False


def superclass_sources(path, src, depth=0):
    """The sources of the class's superclasses, nearest first, as far as they live in this repo."""
    m = re.search(r"\bclass\s+\w+[\s\S]*?\)\s*:\s*([A-Z]\w*)\s*[(<]", src)
    if not m or depth > 5:
        return []
    name = m.group(1)
    imported = re.search(r"^import ([\w.]+\." + name + r")$", src, re.M)
    if imported:
        parent = os.path.join(ROOT, imported.group(1).replace(".", "/") + ".kt")
    else:
        parent = os.path.join(os.path.dirname(path), name + ".kt")
    if not os.path.exists(parent):
        return []
    parent_src = open(parent).read()
    return [parent_src] + superclass_sources(parent, parent_src, depth + 1)

CHECKS = [
    ("no companion build()", lambda src, parents: not re.search(r"\bfun build\b", src)),
    ("tag parsing inline in the class (it[0] == / tag[0] ==)", lambda src, parents: re.search(r"\b(it|tag)\[0\]\s*==\s*\"", src)),
    (
        "linkedXxx() reads raw tags instead of a semantic method",
        lambda src, parents: any(
            re.search(r"tags\.(mapNotNull|firstNotNullOfOrNull)\(" + REFERENCE_PARSERS, body)
            for body in re.findall(r"override fun linked(?:EventIds|AddressIds|PubKeys)\(\)[^\n]*=\s*([^\n]+)", src)
        ),
    ),
    (
        # The provider must match the reference type; one inherited from a base class counts
        # (DvmResponseEvent, GitStatusEvent, BaseDMGroupEvent, ...).
        "parses a/e/p/q but implements no hint provider for it",
        lambda src, parents: missing_provider(src, parents),
    ),
    (
        # A class that decided against search says so in its KDoc: "Not searchable: <why>".
        "reads a title/name/summary/description but is not SearchableEvent",
        lambda src, parents: "Not searchable:" not in src
        and not any("SearchableEvent" in s for s in [src] + parents)
        and re.search(r"\b(TitleTag|SubjectTag|SummaryTag|DescriptionTag|NameTag)::parse", src),
    ),
]


def factory_classes():
    src = open(FACTORY).read()
    imports = {m.group(2): m.group(1) for m in re.finditer(r"^import ([\w.]+\.(\w+))$", src, re.M)}
    body = src[src.index("fun <T : Event> create(") : src.index("fun isKnownKind(")]
    for name in sorted(set(re.findall(r"\b([A-Z]\w*)\(id\b", body)) - {"Event"}):
        if name.startswith("UnrecognizedKind") or name not in imports:
            continue
        path = os.path.join(ROOT, imports[name].replace(".", "/") + ".kt")
        if os.path.exists(path):
            yield name, path


def main():
    scope = sys.argv[1] if len(sys.argv) > 1 else ""
    found = {label: [] for label, _ in CHECKS}
    for name, path in factory_classes():
        if scope not in path:
            continue
        src = open(path).read()
        parents = superclass_sources(path, src)
        for label, check in CHECKS:
            if check(src, parents):
                found[label].append(path[len(ROOT) + 1 :])
    total = 0
    for label, paths in found.items():
        if not paths:
            continue
        total += len(paths)
        print(f"## {label} ({len(paths)})")
        for path in paths:
            print(f"  {path}")
    print(f"{total} findings" if total else "no findings")


if __name__ == "__main__":
    main()
