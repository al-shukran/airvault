#!/usr/bin/env python3
"""One changelog file per app, one fragment per change, collected at release time.

Every pull request that a reader would notice adds one fragment under changelog.d/<app>/, named <slug>.<category>.md, for example add-user-authentication.feature.md. The release build groups the fragments under the Keep a Changelog headings they map to, writes one release section into CHANGELOG-<app>.md, and deletes the consumed files, so two pull requests never edit the same file.

Usage:
  python3 scripts/changelog_fragments.py --check [--base <ref>]
  python3 scripts/changelog_fragments.py --build --version <X.Y.Z> [--date <YYYY-MM-DD>]
  python3 scripts/changelog_fragments.py --self-test

The check always validates the fragments on disk: the name shape, the category, and real prose. With --base (or CHANGELOG_BASE, which CI sets from the diff base) it also reads the diff: product code changed with no fragment added fails, and a changelog edited by hand fails, because normal pull requests never touch those files. Without a base only the files on disk are judged.

Exit codes:
  0  the check passed, or the build wrote the release sections
  1  at least one finding, each naming the file and the rule
  2  usage error
  3  a file this check had to read could not be read, so the clean parts of the run do not cover it
"""

from __future__ import annotations

import datetime
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

EXIT_OK = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2
EXIT_UNREADABLE = 3

ROOT = Path(__file__).resolve().parent.parent
FRAGMENT_DIR = ROOT / "changelog.d"
APPS = ("lensly", "lumio")
CHANGELOGS = {app: ROOT / f"CHANGELOG-{app}.md" for app in APPS}

CATEGORIES = {"feature": "Added", "bugfix": "Fixed", "improvement": "Changed"}
SECTION_ORDER = ("Added", "Changed", "Fixed")

NAME = re.compile(r"^([a-z0-9]+(?:-[a-z0-9]+)*)\.(feature|bugfix|improvement)\.md$")
ISSUES = re.compile(r"issues-(\d+(?:-\d+)*)$")
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
UNRELEASED = re.compile(r"^## \[Unreleased\]")
HEADING = re.compile(r"^##(?!#)")
FENCE = "```"

IGNORED = {"README.md", ".gitkeep", ".gitignore"}
MIN_SLUG = 8
MIN_BODY = 12

SKIP_MARKER = "No user-facing change."
TRIAGE_WORDS = ("security", "deprecat", "remov", "breaking", "cve", "yank")

CODE_PREFIXES = ("app/", "lumio/", "shared/", "core/", "chains/", "abi/")
TEST_PARTS = ("test", "tests", "androidTest", "fixtures", "benchmark", "benchmarks")
TEST_SUFFIXES = ("Test.kt", "Tests.kt", "Test.java", "Tests.java")
FRAGMENT_PREFIX = "changelog.d/"

GITHUB_REPO = "https://github.com/al-shukran/airvault"

USAGE = "usage: changelog_fragments.py --check [--base <ref>] | --build --version <X.Y.Z> [--date <YYYY-MM-DD>] | --self-test"


def fragments(app: str) -> list[Path]:
    """One app's fragments on disk, sorted, without that directory's own documentation."""
    directory = FRAGMENT_DIR / app
    if not directory.is_dir():
        return []
    return sorted(path for path in directory.iterdir() if path.is_file() and path.name not in IGNORED)


def validate_name(path: Path) -> str | None:
    """The naming rule, or None when the name holds."""
    match = NAME.match(path.name)
    if match is None:
        return f"{path.parent.name}/{path.name}: name it <slug>.<category>.md, for example fix-crash-on-startup.bugfix.md, with category feature, bugfix, or improvement"
    if len(match.group(1)) < MIN_SLUG:
        return f"{path.parent.name}/{path.name}: the slug is {len(match.group(1))} characters, at least {MIN_SLUG}, so the name says what changed"
    return None


def validate_body(text: str, name: str) -> list[str]:
    """The prose rules: one real paragraph, and no hard-wrapped continuation line."""
    findings = []
    stripped = text.strip()
    if len(stripped) < MIN_BODY:
        findings.append(f"{name}: write at least {MIN_BODY} characters of user-facing prose, on one line")
        return findings
    in_fence = False
    for line in text.splitlines():
        if line.startswith(FENCE):
            in_fence = not in_fence
        elif not in_fence and line[:1] in (" ", "\t"):
            findings.append(f"{name}: one paragraph per line, so a wrapped continuation line is not finished")
            break
    return findings


def validate_tree() -> list[str]:
    """Every fragment on disk, named and written to the rule."""
    findings = []
    if FRAGMENT_DIR.is_dir():
        for path in FRAGMENT_DIR.iterdir():
            if path.is_file() and path.name not in IGNORED:
                findings.append(f"{FRAGMENT_PREFIX}{path.name}: put fragments under changelog.d/lensly/ or changelog.d/lumio/")
    for app in APPS:
        seen: dict[str, str] = {}
        for path in fragments(app):
            label = f"{app}/{path.name}"
            lowered = path.name.lower()
            if lowered in seen:
                findings.append(f"{label}: duplicates {seen[lowered]} on a case-insensitive filesystem")
                continue
            seen[lowered] = path.name
            problem = validate_name(path)
            if problem is not None:
                findings.append(problem)
                continue
            try:
                findings.extend(validate_body(path.read_text(), label))
            except OSError as error:
                findings.append(f"{label}: could not be read ({error})")
    return findings


def is_test_path(path: str) -> bool:
    """Test, fixture, and benchmark files, which never need a changelog fragment."""
    parts = path.split("/")
    if any(part in TEST_PARTS for part in parts[:-1]):
        return True
    return any(parts[-1].endswith(suffix) for suffix in TEST_SUFFIXES)


def diff_status(base: str) -> tuple[dict[str, str], str | None]:
    """The changed paths between base and the worktree, with git statuses, or an error."""
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-status", base],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )
    except OSError as error:
        return {}, f"git could not run ({error})"
    if proc.returncode != 0:
        return {}, f"git diff failed for base {base}: {proc.stderr.strip()}"
    changes: dict[str, str] = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            changes[parts[2]] = parts[0][:1]
        elif len(parts) == 2:
            changes[parts[1]] = parts[0][:1]
    try:
        proc = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", FRAGMENT_PREFIX],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )
    except OSError:
        proc = None
    if proc is not None and proc.returncode == 0:
        for line in proc.stdout.splitlines():
            changes.setdefault(line, "A")
    return changes, None


def requires_fragment(changes: dict[str, str]) -> tuple[bool, list[str]]:
    """Whether product code changed with no fragment, and which changelogs were edited by hand."""
    any_added = any(
        path.startswith(f"{FRAGMENT_PREFIX}{app}/") and status in ("A", "M", "R", "C")
        for path, status in changes.items()
        for app in APPS
    )
    code_changed = any(path.startswith(prefix) and not is_test_path(path) for path in changes for prefix in CODE_PREFIXES)
    missing = code_changed and not any_added
    direct = []
    for app in APPS:
        touched = any(path.startswith(f"{FRAGMENT_PREFIX}{app}/") for path in changes)
        if f"CHANGELOG-{app}.md" in changes and not touched:
            direct.append(f"CHANGELOG-{app}.md edited by hand; add a fragment under changelog.d/{app}/ instead")
    return missing, direct


def check(base: str | None) -> tuple[list[str], int]:
    """The fragments on disk, plus the diff rules when a base is known."""
    findings = validate_tree()
    if base is None:
        return findings, EXIT_FINDINGS if findings else EXIT_OK
    changes, error = diff_status(base)
    if error is not None:
        return [error], EXIT_UNREADABLE
    missing, direct = requires_fragment(changes)
    if missing:
        findings.append(f"product code changed with no fragment added under changelog.d/<app>/; add one or the change ships undescribed")
    findings.extend(direct)
    return findings, EXIT_FINDINGS if findings else EXIT_OK


def today_utc() -> str:
    """Today's date in UTC as YYYY-MM-DD."""
    return datetime.datetime.now(datetime.timezone.utc).date().isoformat()


def issue_links(stem: str) -> str:
    """The [Issue #N] links a slug's -issues- marker carries, or nothing."""
    match = ISSUES.search(stem)
    if match is None:
        return ""
    return "".join(f" [Issue #{number}]({GITHUB_REPO}/issues/{number})" for number in match.group(1).split("-"))


def triage_hits() -> list[str]:
    """Fragments whose words suggest a Security, Deprecated, or Removed heading, for a human to place."""
    hits = []
    for app in APPS:
        for path in fragments(app):
            try:
                text = f"{path.name} {path.read_text()}".lower()
            except OSError:
                continue
            if any(word in text for word in TRIAGE_WORDS):
                hits.append(f"{app}/{path.name}")
    return hits


def entry_text(path: Path, body: str) -> str:
    """One fragment as one changelog bullet, with its issue links on the end."""
    if body.startswith(("- ", "* ")):
        body = body[2:].lstrip()
    match = NAME.match(path.name)
    stem = match.group(1) if match else path.stem
    return f"- {body}{issue_links(stem)}"


def release_section(app: str, version: str, date: str, frags: list[Path]) -> tuple[str | None, list[str], int]:
    """One app's release section text, or a finding."""
    groups: dict[str, list[str]] = {section: [] for section in SECTION_ORDER}
    skipped = 0
    for path in frags:
        try:
            body = path.read_text().strip()
        except OSError as error:
            return None, [f"{app}/{path.name} could not be read ({error})"], EXIT_UNREADABLE
        if body == SKIP_MARKER:
            skipped += 1
            continue
        category = NAME.match(path.name).group(2)
        groups[CATEGORIES[category]].append(entry_text(path, body))
    section = [f"## [{version}] - {date}", ""]
    for heading in SECTION_ORDER:
        if groups[heading]:
            section += [f"### {heading}", ""] + sorted(groups[heading]) + [""]
    return "\n".join(section), [], skipped


def build(version: str, date: str) -> tuple[list[str], int]:
    """The fragments grouped under their headings, written into both changelogs."""
    findings = validate_tree()
    if findings:
        return findings, EXIT_FINDINGS
    per_app = {app: fragments(app) for app in APPS}
    if not any(per_app.values()):
        return ["no fragments under changelog.d/lensly/ or changelog.d/lumio/, so there is nothing to release"], EXIT_FINDINGS
    texts: dict[str, str] = {}
    for app in APPS:
        try:
            texts[app] = CHANGELOGS[app].read_text()
        except OSError as error:
            return [f"CHANGELOG-{app}.md could not be read ({error})"], EXIT_UNREADABLE
        if re.search(rf"^## \[{re.escape(version)}\]", texts[app], re.MULTILINE):
            return [f"CHANGELOG-{app}.md already has a ## [{version}] section, so the build refuses to write it twice"], EXIT_FINDINGS
    prepared = {}
    for app in APPS:
        section, problems, skipped = release_section(app, version, date, per_app[app])
        if problems:
            return problems, EXIT_UNREADABLE
        prepared[app] = (section, skipped)
    for app in APPS:
        lines = texts[app].splitlines()
        unreleased = next((index for index, line in enumerate(lines) if UNRELEASED.match(line)), None)
        if unreleased is None:
            return [f"CHANGELOG-{app}.md has no ## [Unreleased] section to release under"], EXIT_FINDINGS
        following = next((index for index in range(unreleased + 1, len(lines)) if HEADING.match(lines[index])), len(lines))
        before = "\n".join(lines[:following]).rstrip("\n")
        after = "\n".join(lines[following:])
        insert = prepared[app][0]
        CHANGELOGS[app].write_text(f"{before}\n\n{insert}\n{after}" if after else f"{before}\n\n{insert}\n")
    triage = triage_hits()
    for app in APPS:
        for path in per_app[app]:
            path.unlink()
    for app in APPS:
        count = sum(1 for line in prepared[app][0].splitlines() if line.startswith("- "))
        message = f"changelog: {app} wrote {count} entries under [{version}], removed {len(per_app[app])} fragments"
        if prepared[app][1]:
            message += f" ({prepared[app][1]} marked no user-facing change)"
        print(message)
    if triage:
        print(f"changelog: triage candidates for Security, Deprecated, or Removed: {', '.join(triage)}")
    return [], EXIT_OK


def self_test() -> int:
    """The naming, prose, diff, and build rules against stand-ins."""
    failed = 0

    def expect(label: str, got, want) -> None:
        nonlocal failed
        if got != want:
            failed += 1
            print(f"changelog self-test: {label}: expected {want!r}, got {got!r}")

    expect("good name", validate_name(Path("lensly/fix-crash-on-startup.bugfix.md")), None)
    expect("issue name", validate_name(Path("lumio/fix-session-expiration-issues-45-46.bugfix.md")), None)
    expect("bad suffix", validate_name(Path("lensly/fix-crash.todo.md")) is None, False)
    expect("short slug", validate_name(Path("lensly/fixit.bugfix.md")) is None, False)
    expect("upper case", validate_name(Path("lensly/Fix-Crash.bugfix.md")) is None, False)
    expect("short body", validate_body("Fixed it.", "x.bugfix.md") != [], True)
    expect("brief body", validate_body("Fixed crash.", "x.bugfix.md"), [])
    expect("good body", validate_body("Fixed session expiration handling.", "x.bugfix.md"), [])
    expect("wrapped body", validate_body("Fixed session expiration\n handling.", "x.bugfix.md") != [], True)
    expect("fenced body", validate_body("```\n indented\n```\nFixed session expiration handling.", "x.bugfix.md"), [])
    expect("links", issue_links("fix-session-expiration-issues-45-46"), f" [Issue #45]({GITHUB_REPO}/issues/45) [Issue #46]({GITHUB_REPO}/issues/46)")
    expect("no links", issue_links("add-user-authentication"), "")
    expect("mid-slug marker ignored", issue_links("retire-issues-2026-cleanup"), "")
    expect("date shape", bool(re.match(r"^\d{4}-\d{2}-\d{2}$", today_utc())), True)
    expect("test suffix", is_test_path("app/src/main/java/BackupPathTest.kt"), True)
    expect("tests dir", is_test_path("app/src/test/java/Roundtrip.kt"), True)
    expect("androidTest dir", is_test_path("lumio/src/androidTest/java/Flow.kt"), True)
    expect("fixtures dir", is_test_path("core/fixtures/x.txt"), True)
    expect("prod file", is_test_path("app/src/main/java/MainActivity.kt"), False)

    missing, direct = requires_fragment({"app/src/main/java/MainActivity.kt": "M"})
    expect("code needs fragment", (missing, direct), (True, []))
    missing, direct = requires_fragment({"app/src/main/java/MainActivity.kt": "M", "changelog.d/lensly/fix-crash.bugfix.md": "A"})
    expect("fragment satisfies", (missing, direct), (False, []))
    missing, direct = requires_fragment({"CHANGELOG-lensly.md": "M"})
    expect("direct edit", (missing, direct), (False, ["CHANGELOG-lensly.md edited by hand; add a fragment under changelog.d/lensly/ instead"]))
    missing, direct = requires_fragment({"CHANGELOG-lensly.md": "M", "changelog.d/lensly/fix-crash.bugfix.md": "D"})
    expect("release touches fragments", (missing, direct), (False, []))
    missing, direct = requires_fragment({"README.md": "M"})
    expect("docs need nothing", (missing, direct), (False, []))
    expect("test-only needs nothing", requires_fragment({"app/src/test/java/FlowTest.kt": "M"}), (False, []))
    expect("fixtures need nothing", requires_fragment({"core/fixtures/x.txt": "A"}), (False, []))
    expect("mixed needs fragment", requires_fragment({"app/src/test/java/FlowTest.kt": "M", "app/src/main/java/Flow.kt": "M"}), (True, []))

    with tempfile.TemporaryDirectory() as tmp:
        keep = (FRAGMENT_DIR, CHANGELOGS)
        try:
            stand = Path(tmp)
            globals()["FRAGMENT_DIR"] = stand / "changelog.d"
            globals()["CHANGELOGS"] = {app: stand / f"CHANGELOG-{app}.md" for app in APPS}
            for app in APPS:
                (FRAGMENT_DIR / app).mkdir(parents=True)
                CHANGELOGS[app].write_text("# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-11-22\n\n### Added\n\n- Old entry.\n")
            (FRAGMENT_DIR / "lensly" / "add-user-authentication.feature.md").write_text("Added support for user authentication.\n")
            (FRAGMENT_DIR / "lumio" / "fix-token-disclosure-issues-9.bugfix.md").write_text("Fixed a security token disclosure issue.\n")
            (FRAGMENT_DIR / "lensly" / "refresh-docs-index.improvement.md").write_text(f"{SKIP_MARKER}\n")
            (FRAGMENT_DIR / "README.md").write_text("ignored\n")
            expect("tree valid", validate_tree(), [])
            expect("triage flags security", triage_hits(), ["lumio/fix-token-disclosure-issues-9.bugfix.md"])
            problems, code = build("1.1.0", "2026-11-23")
            expect("build ok", (problems, code), ([], EXIT_OK))
            body = CHANGELOGS["lensly"].read_text()
            expect("section heads", "## [1.1.0] - 2026-11-23" in body, True)
            expect("entry listed", "- Added support for user authentication." in body, True)
            expect("issue linked", "[Issue #9]" in CHANGELOGS["lumio"].read_text(), True)
            expect("marker skipped", SKIP_MARKER in body, False)
            expect("fragment gone", (FRAGMENT_DIR / "lensly" / "add-user-authentication.feature.md").exists(), False)
            expect("marker gone", (FRAGMENT_DIR / "lensly" / "refresh-docs-index.improvement.md").exists(), False)
            expect("readme stays", (FRAGMENT_DIR / "README.md").exists(), True)
            problems, code = build("1.2.0", "2026-11-23")
            expect("empty build refused", code, EXIT_FINDINGS)
        finally:
            globals()["FRAGMENT_DIR"], globals()["CHANGELOGS"] = keep

    with tempfile.TemporaryDirectory() as repo:
        keep_root = ROOT
        try:
            globals()["ROOT"] = Path(repo)
            run = lambda *args: subprocess.run(args, cwd=repo, capture_output=True, check=True)
            run("git", "init", "-q")
            run("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "base")
            (Path(repo) / "app").mkdir()
            (Path(repo) / "app" / "prod.kt").write_text("x\n")
            run("git", "add", "app/prod.kt")
            run("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "prod")
            with open(Path(repo) / "app" / "prod.kt", "a") as handle:
                handle.write("y\n")
            (Path(repo) / "changelog.d" / "lensly").mkdir(parents=True)
            (Path(repo) / "changelog.d" / "lensly" / "fix-untracked-probe.bugfix.md").write_text("Fixed an untracked probe failure.\n")
            changes, error = diff_status("HEAD")
            expect("diff reads", error, None)
            expect("tracked edit seen", changes.get("app/prod.kt"), "M")
            expect("untracked fragment seen", changes.get("changelog.d/lensly/fix-untracked-probe.bugfix.md"), "A")
        finally:
            globals()["ROOT"] = keep_root

    print("changelog self-test: failed" if failed else "changelog self-test: all passed")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        return self_test()
    if argv[:1] == ["--check"]:
        base: str | None = None
        rest = argv[1:]
        if rest[:1] == ["--base"]:
            if len(rest) != 2:
                print(USAGE, file=sys.stderr)
                return EXIT_USAGE
            base = rest[1]
        elif rest:
            print(USAGE, file=sys.stderr)
            return EXIT_USAGE
        base = base or os.environ.get("CHANGELOG_BASE")
        try:
            findings, code = check(base)
        except OSError as error:
            print(f"changelog: {error}", file=sys.stderr)
            return EXIT_UNREADABLE
        for finding in findings:
            print(f"changelog: {finding}")
        if not findings:
            print("changelog: fragments valid" if base is None else "changelog: fragments valid, diff needs nothing more")
        return code
    if argv[:1] == ["--build"]:
        version: str | None = None
        date = today_utc()
        rest = argv[1:]
        while rest:
            flag, *rest = rest
            if flag == "--version" and rest and version is None:
                version, *rest = rest
            elif flag == "--date" and rest:
                date, *rest = rest
            else:
                print(USAGE, file=sys.stderr)
                return EXIT_USAGE
        if version is None or VERSION.match(version) is None:
            print("changelog: --version needs X.Y.Z", file=sys.stderr)
            return EXIT_USAGE
        if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
            print("changelog: --date needs YYYY-MM-DD", file=sys.stderr)
            return EXIT_USAGE
        try:
            findings, code = build(version, date)
        except OSError as error:
            print(f"changelog: {error}", file=sys.stderr)
            return EXIT_UNREADABLE
        for finding in findings:
            print(f"changelog: {finding}")
        return code
    print(USAGE, file=sys.stderr)
    return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
