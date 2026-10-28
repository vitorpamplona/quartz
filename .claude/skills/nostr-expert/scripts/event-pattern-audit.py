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


def code_only(src):
    """The source without imports and comments, so a name there does not count as implemented."""
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    src = re.sub(r"//[^\n]*", "", src)
    return re.sub(r"^import [^\n]*$", "", src, flags=re.M)


def missing_provider(src, parents):
    sources = [code_only(s) for s in [src] + parents]
    for parser in set(re.findall(r"\b(PTag|ETag|ATag|QTag)::parse", code_only(src))):
        if not any(re.search(r"\b" + kind + r"HintProvider\b", s) for kind in PROVIDER_FOR[parser] for s in sources):
            return True
    return False


def supertype(src, name):
    """The class [name] extends: the first type after its declaration's `:`, or None."""
    m = re.search(r"\bclass\s+" + name + r"\b", src)
    if not m:
        return None
    i = m.end()
    for open_char, close_char in (("<", ">"), ("(", ")")):
        while i < len(src) and src[i].isspace():
            i += 1
        if i < len(src) and src[i] == open_char:
            depth = 0
            while i < len(src):
                depth += {open_char: 1, close_char: -1}.get(src[i], 0)
                i += 1
                if depth == 0:
                    break
    m = re.match(r"\s*:\s*([A-Z]\w*)", src[i:])
    return m.group(1) if m else None


def superclass_sources(path, src, name, depth=0):
    """The sources of [name]'s superclasses, nearest first, as far as they live in this repo."""
    parent_name = supertype(src, name)
    if not parent_name or depth > 5:
        return []
    imported = re.search(r"^import ([\w.]+\." + parent_name + r")$", src, re.M)
    if imported:
        parent = os.path.join(ROOT, imported.group(1).replace(".", "/") + ".kt")
    else:
        parent = os.path.join(os.path.dirname(path), parent_name + ".kt")
    if not os.path.exists(parent):
        return []
    parent_src = open(parent).read()
    return [parent_src] + superclass_sources(parent, parent_src, parent_name, depth + 1)


def has_builder(src):
    """A `build()` (generic or not), or any companion function that returns an unsigned EventTemplate:
    a kind-specific builder such as `reply()` or `buildMove()` counts."""
    if re.search(r"\bfun\s+(?:<[^>]*>\s*)?build\(", src):
        return True
    companion = src[src.find("companion object") :] if "companion object" in src else ""
    return bool(re.search(r"\beventTemplate\s*[(<]|\)\s*:\s*EventTemplate<", companion))


def class_kdoc(src, name):
    """The KDoc directly above [name]'s declaration (annotations and modifiers may sit between)."""
    m = re.search(r"/\*\*((?:(?!\*/)[\s\S])*)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:open|abstract|data|sealed)\s+)*class\s+" + name + r"\b", src)
    return m.group(1) if m else ""


CHECKS = [
    ("no companion build()", lambda src, parents, name: not has_builder(src)),
    ("tag parsing inline in the class (it[0] == / tag[0] ==)", lambda src, parents, name: re.search(r"\b(it|tag)\[0\]\s*==\s*\"", src)),
    (
        "linkedXxx() reads raw tags instead of a semantic method",
        lambda src, parents, name: any(
            re.search(r"tags\.(mapNotNull|firstNotNullOfOrNull)\(" + REFERENCE_PARSERS, body)
            for body in re.findall(r"override fun linked(?:EventIds|AddressIds|PubKeys)\(\)[^\n]*=\s*([^\n]+)", src)
        ),
    ),
    (
        # The provider must match the reference type; one inherited from a base class counts
        # (DvmResponseEvent, GitStatusEvent, BaseDMGroupEvent, ...).
        "parses a/e/p/q but implements no hint provider for it",
        lambda src, parents, name: missing_provider(src, parents),
    ),
    (
        # A class that decided against search says so in its KDoc: "Not searchable: <why>".
        "reads a title/name/summary/description but is not SearchableEvent",
        lambda src, parents, name: "Not searchable:" not in class_kdoc(src, name)
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
        parents = superclass_sources(path, src, name)
        for label, check in CHECKS:
            if check(src, parents, name):
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
