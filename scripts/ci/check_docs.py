"""Check tracked Markdown fences and repository-local links without network I/O."""

from collections import Counter
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import unquote, urlsplit


def slug(title):
    return "".join(
        c for c in title.lower()
        if c in "-_ " or unicodedata.category(c)[0] in "LN"
    ).replace(" ", "-")


def parse_document(text):
    headings, links, counts = set(), [], Counter()
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence:
            continue
        heading = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if heading:
            name = slug(heading[1].replace("`", ""))
            suffix = f"-{counts[name]}" if counts[name] else ""
            counts[name] += 1
            headings.add(name + suffix)
        links.extend(re.findall(r"!?\[[^\]]*\]\(([^\s)]+)\)", line))
    if fence:
        raise ValueError("Unclosed fenced code block")
    return headings, links


def check_document(path, root):
    headings, links = parse_document(path.read_text(encoding="utf-8"))
    for target in links:
        parts = urlsplit(target)
        if parts.scheme or parts.netloc:
            continue  # External URLs are not fetched by this deterministic check.
        linked = (root if parts.path.startswith("/") else path.parent) / unquote(parts.path).lstrip("/")
        linked = linked.resolve() if parts.path else path
        if not linked.is_relative_to(root):
            raise ValueError(f"Link escapes repository: {target}")
        if not linked.exists():
            raise ValueError(f"Missing local link: {target}")
        if parts.fragment and linked.suffix == ".md":
            destination = headings if linked == path else parse_document(linked.read_text(encoding="utf-8"))[0]
            if unquote(parts.fragment) not in destination:
                raise ValueError(f"Missing heading: {target}")


def main():
    root = Path.cwd().resolve()
    tracked = subprocess.check_output(["git", "ls-files", "-z", "README.md", "docs/*.md", "docs/**/*.md"])
    paths = [root / p.decode("utf-8") for p in tracked.split(b"\0") if p]
    failures = []
    for path in paths:
        try:
            check_document(path, root)
        except ValueError as error:
            failures.append(f"{path.relative_to(root)}: {error}")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"Checked {len(paths)} tracked Markdown documents: local links, headings and fences.")


if __name__ == "__main__":
    main()
