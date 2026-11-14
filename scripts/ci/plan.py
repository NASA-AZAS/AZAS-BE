"""Choose app CI without losing changes that have not been deployed yet."""

import json
import os
from pathlib import Path
import re
import subprocess


def is_documentation(path):
    return path == "README.md" or (
        path.startswith("docs/") and path.endswith(".md")
    )


def git(*args):
    return subprocess.check_output(["git", *args], stderr=subprocess.PIPE)


def changed_files(base, head, pull_request=False):
    if not all(re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (base, head)):
        raise ValueError("Invalid comparison commit")
    if not pull_request:
        git("merge-base", "--is-ancestor", base, head)
    separator = "..." if pull_request else ".."
    # Keep both names of a rename; moving code into docs must still run app CI.
    output = git("diff", "--name-only", "--no-renames", "-z", f"{base}{separator}{head}")
    return output.decode("utf-8").rstrip("\0").split("\0") if output else []


def api(repository, resource):
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repository):
        raise ValueError("Invalid repository")
    result = subprocess.check_output(
        ["gh", "api", f"repos/{repository}/{resource}"],
        stderr=subprocess.PIPE, timeout=30,
    )
    return json.loads(result)


def latest_deployment(repository):
    # A successful docs-only workflow is NOT evidence of a deployment.
    # Bound API work; an absent/unknown baseline means full CI, never a skip.
    runs = api(repository, "actions/workflows/ci.yml/runs?branch=main&status=completed&per_page=20")
    deployments = []
    for run in runs["workflow_runs"]:
        if run["event"] not in ("push", "workflow_dispatch"):
            continue
        if (run.get("head_repository") or {}).get("full_name") != repository:
            continue
        jobs = api(repository, f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
        for job in jobs["jobs"]:
            if job["name"] == "Deploy to EC2" and job["conclusion"] == "success":
                deployments.append((job["completed_at"], run["head_sha"]))
    # Completion time also handles a successful retry of an older workflow run.
    return max(deployments)[1] if deployments else None


def choose_plan(event_name, event, head, ref, repository):
    if event_name == "workflow_dispatch":
        return True, "Manual run: always run full app CI."
    if event_name == "pull_request":
        base = event["pull_request"]["base"]["sha"]
        head = event["pull_request"]["head"]["sha"]
    elif event_name == "push" and ref == "refs/heads/main":
        # Concurrency is not FIFO. Do not let an old queued push roll main back.
        current_main = api(repository, "git/ref/heads/main")["object"]["sha"]
        if current_main != head:
            return False, "Superseded main push: a newer main run owns deployment."
        try:
            base = latest_deployment(repository)
        except (subprocess.SubprocessError, ValueError, KeyError, TypeError):
            return True, "Deployment history unavailable: run full app CI."
        if not base:
            return True, "No confirmed deployment in recent history: run full app CI."
    else:
        return True, "Unclassified event: run full app CI."
    try:
        paths = changed_files(base, head, pull_request=event_name == "pull_request")
    except (subprocess.SubprocessError, ValueError, UnicodeError):
        return True, "Comparison unavailable: run full app CI."
    if all(is_documentation(path) for path in paths):
        return False, "Only documentation changed (or no changes) since the comparison commit."
    return True, "App, configuration or unclassified files changed: run full app CI."


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    app, reason = choose_plan(
        os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_SHA"],
        os.environ["GITHUB_REF"], os.environ["GITHUB_REPOSITORY"],
    )
    value = str(app).lower()
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"app={value}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"## CI plan\n\n- Full app CI: **{value}**\n- {reason}\n")
    print(f"Full app CI: {value}. {reason}")


if __name__ == "__main__":
    main()
