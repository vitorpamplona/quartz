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

CHECKS = [
    ("no companion build()", lambda src: not re.search(r"\bfun build\b", src)),
    ("tag parsing inline in the class (it[0] == / tag[0] ==)", lambda src: re.search(r"\b(it|tag)\[0\]\s*==\s*\"", src)),
    (
        "linkedXxx() reads raw tags instead of a semantic method",
        lambda src: any(
            re.search(r"tags\.(mapNotNull|firstNotNullOfOrNull)\(" + REFERENCE_PARSERS, body)
            for body in re.findall(r"override fun linked(?:EventIds|AddressIds|PubKeys)\(\)[^\n]*=\s*([^\n]+)", src)
        ),
    ),
    (
        "parses a/e/p/q but implements no hint provider",
        lambda src: re.search(r"\b(PTag|ETag|ATag|QTag)::parse", src) and not re.search(r"(PubKey|Event|Address)HintProvider", src),
    ),
    (
        "reads a title/name/summary/description but is not SearchableEvent",
        lambda src: "SearchableEvent" not in src and re.search(r"\b(TitleTag|SubjectTag|SummaryTag|DescriptionTag|NameTag)::parse", src),
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
        for label, check in CHECKS:
            if check(src):
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
