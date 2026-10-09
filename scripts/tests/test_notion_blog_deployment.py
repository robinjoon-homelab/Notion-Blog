import copy
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy


class NotionBlogDeploymentTests(unittest.TestCase):
    bootstrap = Path(__file__).resolve().parents[2] / ".github" / "deployment.json"

    def setUp(self):
        self.sha = "a" * 40
        self.tag = f"sha-{self.sha}-run-123-1"
        self.environment = {
            "APP_NAME": "notion-blog",
            "REGISTRY_HOST": "registry.homelab.robinjoon.xyz",
            "REGISTRY_IMAGE": "apps/notion-blog",
            "GITHUB_REPOSITORY": "another-owner/renamed-blog-repository",
            "GITHUB_REPOSITORY_ID": "987654321",
            "GITHUB_SHA": self.sha,
            "GITHUB_RUN_ID": "123",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_REF": "refs/heads/master",
            "GH_TOKEN": "application-test-token",
            "HARNESS_ACTIONS_TOKEN": "harness-test-token",
            "IMAGE_TAG": self.tag,
        }
        self.repository = "registry.homelab.robinjoon.xyz/apps/notion-blog"

    def target(self):
        self.assertTrue(self.bootstrap.is_file(), "Notion Blog needs an explicit bootstrap runtime contract")
        return deploy.configuration(self.environment, self.bootstrap)

    def test_bootstrap_records_the_existing_database_domain_and_runtime_environment(self):
        self.assertTrue(self.bootstrap.is_file(), "Notion Blog needs an explicit bootstrap runtime contract")
        settings = json.loads(self.bootstrap.read_text())

        self.assertEqual(set(settings), {"database", "host", "env"})
        self.assertEqual(settings["database"], "notion_blog")
        self.assertEqual(settings["host"], "blog.homelab.robinjoon.xyz")
        entries = settings["env"]
        by_name = {entry["name"]: {key: value for key, value in entry.items() if key != "name"} for entry in entries}
        self.assertEqual(len(by_name), len(entries))
        self.assertEqual(by_name, {
            "DB_PORT": {"secretKeyRef": {"name": "shared-db-app", "key": "port"}},
            "SPRING_DATASOURCE_URL": {"value": "jdbc:postgresql://$(DB_HOST):$(DB_PORT)/notion_blog"},
            "SPRING_DATASOURCE_USERNAME": {"secretKeyRef": {"name": "shared-db-app", "key": "username"}},
            "SPRING_DATASOURCE_PASSWORD": {"secretKeyRef": {"name": "shared-db-app", "key": "password"}},
            "NOTION_TOKEN": {"secretKeyRef": {"name": "notion-blog-runtime", "key": "NOTION_TOKEN"}},
            "NOTION_SETTINGS_DATA_SOURCE_ID": {"secretKeyRef": {"name": "notion-blog-runtime", "key": "NOTION_SETTINGS_DATA_SOURCE_ID"}},
            "NOTION_API_VERSION": {"value": "2026-03-11"},
            "BLOG_SYNCHRONIZATION_ENABLED": {"value": "true"},
            "BLOG_SYNCHRONIZATION_INTERVAL_MS": {"value": "60000"},
            "BLOG_SYNCHRONIZATION_SUCCESS_INTERVAL": {"value": "1m"},
            "BLOG_PUBLIC_BASE_URL": {"value": "https://blog.homelab.robinjoon.xyz"},
            "SPRING_PROFILES_ACTIVE": {"value": "prod"},
        })
        names = [entry["name"] for entry in entries]
        self.assertLess(names.index("DB_PORT"), names.index("SPRING_DATASOURCE_URL"))
        self.assertNotIn("DB_HOST", names)
        self.assertNotIn("BLOG_BASE_URL", names)

    def test_new_blog_payload_keeps_complete_runtime_and_the_configured_image(self):
        target = self.target()
        payload = deploy.creation_payload(target, self.tag)
        values = payload["values"]
        workload = values["workload"]
        container, = workload["containers"]

        self.assertEqual(payload["image"], f"{self.repository}:{self.tag}")
        self.assertEqual(payload["dbName"], "notion_blog")
        self.assertEqual(workload["replicas"], 1)
        self.assertEqual(container["name"], "app")
        self.assertEqual(container["image"], {"repository": self.repository, "tag": self.tag})
        self.assertEqual(container["ports"], [{"name": "http", "containerPort": 8080}])
        self.assertEqual(container["env"], json.loads(self.bootstrap.read_text())["env"])
        self.assertNotIn("serviceAccountName", workload)
        self.assertEqual(deploy.application_urls(values), ["https://blog.homelab.robinjoon.xyz"])

    def test_metadata_uses_explicit_blog_identity_after_repository_rename_without_remote_calls(self):
        self.target()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            environment = {**self.environment, "GITHUB_OUTPUT": str(output)}
            with patch.dict(os.environ, environment, clear=True), patch.object(sys, "argv", ["deploy.py", "metadata"]):
                with patch.object(deploy, "gh") as gh, patch.object(deploy, "DeployApi") as api:
                    deploy.main()
            outputs = dict(line.split("=", 1) for line in output.read_text().splitlines())

        self.assertEqual(outputs, {"app": "notion-blog", "tag": self.tag, "image": f"{self.repository}:{self.tag}"})
        gh.assert_not_called()
        api.assert_not_called()

    def test_absent_blog_creates_the_complete_runtime_then_confirms_main_without_an_extra_release(self):
        target = self.target()
        payload = deploy.creation_payload(target, self.tag)
        api = Mock()
        api.request.side_effect = [None, {"runId": 42}, {"state": "committed"}, payload["values"]]
        release = Mock()

        actual = deploy.deploy(api, target, self.tag, release)

        self.assertEqual(actual, payload["values"])
        self.assertEqual(api.request.call_args_list, [
            call("GET", "/v1/apps/notion-blog"),
            call("POST", "/v1/apps/notion-blog", payload),
            call("GET", "/v1/runs/42"),
            call("GET", "/v1/apps/notion-blog"),
        ])
        release.assert_not_called()

    def test_existing_blog_keeps_all_database_domain_environment_and_service_account_values(self):
        target = self.target()
        original = self.existing_values(target)
        preserved = copy.deepcopy(original)
        expected = copy.deepcopy(original)
        expected["workload"]["containers"][0]["image"]["tag"] = self.tag
        proposed = {**target, "database": "different_database", "url": "https://unused-bootstrap.example", "env": [], "serviceAccountName": "different-account"}
        api = Mock()
        api.request.side_effect = [original, expected]
        release = Mock()

        with patch.object(deploy, "creation_payload", side_effect=AssertionError("Existing workloads must not reuse bootstrap settings")):
            actual = deploy.deploy(api, proposed, self.tag, release)

        self.assertEqual(actual, expected)
        self.assertEqual(original, preserved)
        release.assert_called_once_with("notion-blog", self.tag)
        self.assertEqual(api.request.call_args_list, [call("GET", "/v1/apps/notion-blog")] * 2)
        self.assertEqual(deploy.application_urls(actual), ["https://blog.homelab.robinjoon.xyz"])

    def test_manual_master_release_scopes_tokens_and_reports_the_observed_blog_url(self):
        target = self.target()
        old = self.existing_values(target)
        current = copy.deepcopy(old)
        current["workload"]["containers"][0]["image"]["tag"] = self.tag
        target["url"] = "https://unused-bootstrap.example"
        api = Mock()
        api.request.side_effect = [old, current]
        output = io.StringIO()
        environment = {**self.environment, "GITHUB_EVENT_NAME": "workflow_dispatch"}

        with patch.dict(os.environ, environment, clear=True), patch.object(deploy, "gh", side_effect=[self.sha, ""]) as gh:
            with patch.object(deploy, "DeployApi", return_value=api) as factory, redirect_stdout(output):
                deploy.apply(target, self.tag)

        factory.assert_called_once_with("harness-test-token")
        self.assertEqual(gh.call_args_list, [
            call(["api", "repos/another-owner/renamed-blog-repository/git/ref/heads/master", "--jq", ".object.sha"], "application-test-token"),
            call(["workflow", "run", "release-workload-image.yml", "--repo", deploy.HARNESS, "--ref", "main", "-f", "app=notion-blog", "-f", "container=app", "-f", f"tag={self.tag}"], "harness-test-token"),
        ])
        self.assertIn(f"Harness main now records `{self.repository}:{self.tag}`", output.getvalue())
        self.assertIn("https://blog.homelab.robinjoon.xyz", output.getvalue())
        self.assertNotIn("unused-bootstrap.example", output.getvalue())
        self.assertIn("not verified by this job", output.getvalue())

    def test_pr_and_stale_master_jobs_never_contact_the_harness(self):
        target = self.target()
        with patch.dict(os.environ, {**self.environment, "GITHUB_EVENT_NAME": "pull_request"}, clear=True):
            with patch.object(deploy, "gh") as gh, patch.object(deploy, "DeployApi") as api:
                with self.assertRaisesRegex(RuntimeError, "restricted"):
                    deploy.apply(target, self.tag)
                gh.assert_not_called()
                api.assert_not_called()
        for event in ["push", "workflow_dispatch"]:
            with self.subTest(event=event), patch.dict(os.environ, {**self.environment, "GITHUB_EVENT_NAME": event}, clear=True):
                with patch.object(deploy, "gh", return_value="b" * 40), patch.object(deploy, "DeployApi") as api, redirect_stdout(io.StringIO()):
                    deploy.apply(target, self.tag)
                api.assert_not_called()

    def test_final_wrong_tag_repository_or_unavailable_values_never_claim_a_successful_blog_release(self):
        target = self.target()
        old = self.existing_values(target)
        wrong_repository = copy.deepcopy(old)
        wrong_repository["workload"]["containers"][0]["image"] = {"repository": "registry.example.invalid/other", "tag": self.tag}
        for final_values in [old, wrong_repository, None, {}]:
            api = Mock()
            api.request.side_effect = [old, final_values]
            output = io.StringIO()
            with self.subTest(final_values=final_values), patch.dict(os.environ, self.environment, clear=True):
                with patch.object(deploy, "gh", side_effect=[self.sha, ""]), patch.object(deploy, "DeployApi", return_value=api):
                    with patch.object(deploy, "POLL_ATTEMPTS", 1), patch.object(deploy.time, "sleep"), redirect_stdout(output):
                        with self.assertRaises(RuntimeError):
                            deploy.apply(target, self.tag)
            self.assertNotIn("Harness main now records", output.getvalue())

    def existing_values(self, target):
        values = deploy.creation_payload(target, "previous")['values']
        values["database"] = {"name": "notion_blog"}
        values["workload"]["serviceAccountName"] = "existing-blog-account"
        values["workload"]["containers"][0]["env"].append({"name": "EXISTING_SETTING", "value": "retain-me"})
        return values


if __name__ == "__main__":
    unittest.main()
