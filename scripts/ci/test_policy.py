import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_docs
import plan


A, B, C = "a" * 40, "b" * 40, "c" * 40
REPO = "NASA-AZAS/AZAS-BE"


class PlanTests(unittest.TestCase):
    def choose(self, event="pull_request", head=B):
        payload = {"pull_request": {"base": {"sha": A}, "head": {"sha": head}}}
        return plan.choose_plan(event, payload, head, "refs/heads/main", REPO)[0]

    def test_docs_allowlist_is_narrow(self):
        for path in ("README.md", "docs/logging.md", "docs/nested/start.md"):
            self.assertTrue(plan.is_documentation(path), path)
        for path in ("build.gradle", "Dockerfile", ".github/workflows/ci.yml",
                     "scripts/ci/plan.py", "src/main/resources/db/migration/README.md",
                     "docs/script.py", "docs/README.MD", "README.md.java"):
            self.assertFalse(plan.is_documentation(path), path)

    def test_manual_run_always_full_without_git_or_api(self):
        with patch.object(plan, "api", side_effect=AssertionError), \
             patch.object(plan, "changed_files", side_effect=AssertionError):
            self.assertTrue(self.choose("workflow_dispatch"))

    def test_pr_docs_and_mixed_changes(self):
        for paths, expected in ((["README.md", "docs/logging.md"], False),
                                (["docs/logging.md", "build.gradle"], True),
                                ([".github/workflows/ci.yml"], True), ([], False)):
            with self.subTest(paths=paths), patch.object(plan, "changed_files", return_value=paths) as diff, \
                 patch.object(plan, "api", side_effect=AssertionError):
                self.assertEqual(expected, self.choose())
                diff.assert_called_once_with(A, B, pull_request=True)

    def test_unavailable_diff_runs_full(self):
        with patch.object(plan, "changed_files", side_effect=ValueError):
            self.assertTrue(self.choose())

    def test_main_compares_deployed_sha_not_previous_push(self):
        with patch.object(plan, "api", return_value={"object": {"sha": C}}), \
             patch.object(plan, "latest_deployment", return_value=A), \
             patch.object(plan, "changed_files", return_value=["src/App.java", "docs/logging.md"]) as diff:
            self.assertTrue(self.choose("push", C))
            diff.assert_called_once_with(A, C, pull_request=False)

    def test_main_docs_after_successful_deploy_skips(self):
        with patch.object(plan, "api", return_value={"object": {"sha": B}}), \
             patch.object(plan, "latest_deployment", return_value=A), \
             patch.object(plan, "changed_files", return_value=["docs/logging.md"]):
            self.assertFalse(self.choose("push"))

    def test_stale_main_push_does_not_roll_back(self):
        with patch.object(plan, "api", return_value={"object": {"sha": C}}), \
             patch.object(plan, "latest_deployment", side_effect=AssertionError):
            self.assertFalse(self.choose("push"))

    def test_unavailable_history_runs_full(self):
        for baseline in (None, subprocess.CalledProcessError(1, ["gh", "api"])):
            with self.subTest(baseline=baseline), \
                 patch.object(plan, "api", return_value={"object": {"sha": B}}), \
                 patch.object(plan, "latest_deployment", side_effect=baseline if isinstance(baseline, Exception) else None,
                              return_value=baseline):
                self.assertTrue(self.choose("push"))

    def test_unknown_event_runs_full(self):
        self.assertTrue(self.choose("unknown"))

    def test_main_ref_failure_blocks_plan_instead_of_silently_skipping(self):
        with patch.object(plan, "api", side_effect=subprocess.CalledProcessError(1, ["gh", "api"])):
            with self.assertRaises(subprocess.CalledProcessError):
                self.choose("push")

    def test_history_requires_successful_deploy_job_and_uses_completion_time(self):
        runs = [{"id": n, "event": event, "head_sha": sha, "head_repository": {"full_name": REPO}}
                for n, event, sha in ((1, "push", C), (2, "push", B), (3, "workflow_dispatch", A),
                                     (4, "pull_request", C))]
        jobs = {1: [{"name": "Deploy to EC2", "conclusion": "skipped", "completed_at": "2026-01-04"}],
                2: [{"name": "Deploy to EC2", "conclusion": "success", "completed_at": "2026-01-02"}],
                3: [{"name": "Deploy to EC2", "conclusion": "success", "completed_at": "2026-01-03"}]}
        def api(repo, resource):
            if resource.startswith("actions/workflows/"):
                return {"workflow_runs": runs}
            return {"jobs": jobs[int(resource.split('/')[2])]}
        with patch.object(plan, "api", side_effect=api):
            self.assertEqual(A, plan.latest_deployment(REPO))

    def test_main_writes_boolean_output_and_readable_summary(self):
        with tempfile.TemporaryDirectory(prefix="azas-ci-output-") as directory:
            root = Path(directory)
            event = root / "event.json"
            event.write_text("{}", encoding="utf-8")
            env = {"GITHUB_EVENT_PATH": str(event), "GITHUB_EVENT_NAME": "workflow_dispatch",
                   "GITHUB_SHA": B, "GITHUB_REF": "refs/heads/main", "GITHUB_REPOSITORY": REPO,
                   "GITHUB_OUTPUT": str(root / "output"), "GITHUB_STEP_SUMMARY": str(root / "summary")}
            with patch.dict(os.environ, env):
                plan.main()
            self.assertEqual("app=true\n", (root / "output").read_text())
            self.assertIn("Manual run", (root / "summary").read_text())


class GitComparisonTests(unittest.TestCase):
    def test_real_history_pending_code_rename_deletion_and_diverged_base(self):
        with tempfile.TemporaryDirectory(prefix="azas-ci-git-") as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE).strip().decode()
            def commit(message):
                git("add", "--all")
                git("commit", "-m", message)
                return git("rev-parse", "HEAD")
            git("init")
            git("config", "user.name", "CI fixture")
            git("config", "user.email", "ci@example.invalid")
            git("config", "commit.gpgsign", "false")
            (root / "README.md").write_text("# Start\n", encoding="utf-8")
            (root / "App.java").write_text("class App {}\n", encoding="utf-8")
            baseline = commit("baseline")
            (root / "App.java").write_text("class App { int count; }\n", encoding="utf-8")
            code = commit("pending code")
            (root / "README.md").write_text("# Updated\n", encoding="utf-8")
            docs = commit("docs after pending code")
            (root / "docs").mkdir()
            git("mv", "App.java", "docs/App.md")
            renamed = commit("move code into docs")
            (root / "docs/App.md").unlink()
            deleted = commit("delete docs")
            (root / "README.md").write_text("# Feature\n", encoding="utf-8")
            feature = commit("feature branch")
            git("checkout", "--detach", deleted)
            (root / "Other.java").write_text("class Other {}\n", encoding="utf-8")
            diverged = commit("base advanced")
            with patch.object(plan, "git", side_effect=lambda *args: subprocess.check_output(
                    ["git", "-C", str(root), *args], stderr=subprocess.PIPE)):
                self.assertEqual(["README.md"], plan.changed_files(code, docs))
                self.assertIn("App.java", plan.changed_files(baseline, docs))
                self.assertEqual({"App.java", "docs/App.md"}, set(plan.changed_files(docs, renamed)))
                self.assertEqual(["docs/App.md"], plan.changed_files(renamed, deleted))
                self.assertEqual(["README.md"], plan.changed_files(diverged, feature, pull_request=True))
                with self.assertRaises(subprocess.CalledProcessError):
                    plan.changed_files(diverged, feature)


class DocumentationTests(unittest.TestCase):
    def test_headings_duplicate_anchors_unicode_and_code_examples(self):
        headings, links = check_docs.parse_document("# 제목·예시\n## Same\n## Same\n```md\n[ignore](missing)\n```\n[go](#same-1)\n")
        self.assertEqual({"제목예시", "same", "same-1"}, headings)
        self.assertEqual(["#same-1"], links)

    def test_unclosed_fence_fails(self):
        with self.assertRaises(ValueError):
            check_docs.parse_document("```sql\nfields @message\n")

    def test_missing_local_file_and_heading_fail_external_links_are_not_fetched(self):
        with tempfile.TemporaryDirectory(prefix="azas-ci-docs-") as directory:
            root = Path(directory).resolve()
            doc = root / "README.md"
            for text, valid in (("# Title\n[go](#title)\n[external](https://example.invalid)", True),
                                ("[missing](none.md)", False), ("[missing](#absent)", False),
                                ("[escape](../outside.md)", False)):
                doc.write_text(text, encoding="utf-8")
                if valid:
                    check_docs.check_document(doc, root)
                else:
                    with self.assertRaises(ValueError):
                        check_docs.check_document(doc, root)


if __name__ == "__main__":
    unittest.main()
