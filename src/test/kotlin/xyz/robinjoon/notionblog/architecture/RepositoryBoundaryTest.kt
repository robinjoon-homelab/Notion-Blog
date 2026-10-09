package xyz.robinjoon.notionblog.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RepositoryBoundaryTest {
    @Test
    fun `deployment harness manifests are not owned by this repository`() {
        assertThat(Path.of("deploy/helm")).doesNotExist()
    }

    @Test
    fun `docker image runs the verified application with JVM defaults`() {
        val dockerfile = Files.readString(Path.of("Dockerfile"))
        val dockerignore = Files.readString(Path.of(".dockerignore"))

        assertThat(dockerfile).contains("FROM eclipse-temurin:25-jre-noble")
        assertThat(dockerfile).contains("COPY --chown=10001:10001 build/libs/application.jar application.jar")
        assertThat(dockerfile).contains("USER 10001:10001", "ENTRYPOINT [\"java\", \"-jar\", \"/app/application.jar\"]")
        assertThat(dockerfile).doesNotContain("25-jdk", "./gradlew", "bootJar")
        assertThat(dockerfile).doesNotContain(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "JAVA_OPTS",
            "_JAVA_OPTIONS",
            "-Xms",
            "-Xmx",
            "-XX:",
        )
        assertThat(dockerignore).startsWith("**\n").contains("!build/libs/application.jar")
    }

    @Test
    fun `ci publishes immutable images without owning the deployment harness`() {
        val workflowPath = Path.of(".github/workflows/ci.yml")

        assertThat(workflowPath).isRegularFile()

        val workflow = Files.readString(workflowPath)

        assertThat(workflow).contains("./gradlew build")
        assertThat(workflow).contains("linux/amd64")
        assertThat(workflow).contains(
            "APP_NAME: notion-blog",
            "REGISTRY_HOST: \${{ vars.HOMELAB_REGISTRY_HOST }}",
            "REGISTRY_IMAGE: \${{ vars.HOMELAB_REGISTRY_IMAGE }}",
            "run: python3 scripts/deploy.py metadata",
        )
        assertThat(workflow).contains("push: true")
        assertThat(workflow).contains("github.ref == 'refs/heads/master'")
        assertThat(workflow).contains("username: \${{ env.REGISTRY_USERNAME }}")
        assertThat(workflow).contains("password: \${{ env.REGISTRY_PASSWORD }}")
        assertThat(workflow)
            .doesNotContain("HARNESS_DEPLOY_KEY", "tools/platform.py", "git push origin HEAD:main")
        assertThat(workflow).doesNotContain("bootstrap-homelab", "deploy-*", "kubectl", "KUBECONFIG")
    }

    @Test
    fun `ci verifies the target image on harness main after publishing the tested artifact`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val publishJob = workflow.substringAfter("  publish:")
        val artifactDownload = publishJob.indexOf("- name: Download the tested application")
        val imagePushStep = publishJob.indexOf("docker/build-push-action@")
        val releaseRequestStep = publishJob.indexOf("run: python3 scripts/deploy.py apply")

        assertThat(artifactDownload).isGreaterThanOrEqualTo(0)
        assertThat(publishJob.indexOf("docker/build-push-action@")).isGreaterThan(artifactDownload)
        assertThat(imagePushStep).isGreaterThanOrEqualTo(0)
        assertThat(releaseRequestStep).isGreaterThan(imagePushStep)
        assertThat(workflow).contains(
            "group: notion-blog-deploy",
            "cancel-in-progress: false",
            "GH_TOKEN: \${{ github.token }}",
            "tags: \${{ steps.metadata.outputs.image }}",
            "IMAGE_TAG: \${{ steps.metadata.outputs.tag }}",
            "run: python3 scripts/deploy.py apply",
            "path: build/libs/application.jar",
            "path: build/libs/",
            "name: application-\${{ github.sha }}",
        )
        assertThat(publishJob).doesNotContain(":latest", "if: always()", "continue-on-error: true", "./gradlew")
        assertThat(workflow.split("docker/build-push-action@")).hasSize(2)
    }

    @Test
    fun `ci validates explicit deployment identity before requesting any platform credentials`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val publishJob = workflow.substringAfter("  publish:")
        val metadata = publishJob.indexOf("run: python3 scripts/deploy.py metadata")
        val credentialLoad = publishJob.indexOf("load-ci-secrets@")

        assertThat(metadata).isGreaterThanOrEqualTo(0)
        assertThat(credentialLoad).isGreaterThan(metadata)
        assertThat(publishJob).contains("id: metadata", "APP_NAME: notion-blog")
        assertThat(publishJob).contains(
            "github.ref == 'refs/heads/master'",
            "github.event_name == 'push' || github.event_name == 'workflow_dispatch'",
        )
        assertThat(publishJob).doesNotContain("refs/heads/main", "github.repository_id", "vars.HOMELAB_APP_NAME")
    }

    @Test
    fun `ci checks pull request source commits and rejects formatting changes`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val verification = workflow.substringBefore("  publish:")

        assertThat(verification).contains(
            "repository: \${{ github.event.pull_request.head.repo.full_name || github.repository }}",
            "ref: \${{ github.event.pull_request.head.sha || github.sha }}",
            "persist-credentials: false",
            "cache-read-only: \${{ github.event_name == 'pull_request' }}",
            "run: git diff --exit-code",
            "run: python3 -m unittest discover -s scripts/tests -v",
            "retention-days: 7",
            "quality-rules/build/reports/",
        )
        assertThat(workflow).doesNotContain("pull_request_target", "continue-on-error: true")
    }

    @Test
    fun `ci loads both SMS credential objects before validation without legacy secret references`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val publishJob = workflow.substringAfter("  publish:")
        val registryLoad = publishJob.indexOf("- name: Load registry credentials from SMS")
        val harnessLoad = publishJob.indexOf("- name: Load harness credentials from SMS")
        val validation = publishJob.indexOf("- name: Validate publish configuration")

        assertThat(registryLoad).isGreaterThanOrEqualTo(0)
        assertThat(harnessLoad).isGreaterThan(registryLoad)
        assertThat(validation).isGreaterThan(harnessLoad)
        assertThat(publishJob).contains("id-token: write", "app: zot", "app: harness")
        assertThat(publishJob.split("load-ci-secrets@v1.0.0")).hasSize(3)
        assertThat(workflow.substringBefore("  publish:")).doesNotContain("id-token: write")
        assertThat(workflow).doesNotContain(
            "secrets.HOMELAB_REGISTRY_USERNAME",
            "secrets.HOMELAB_REGISTRY_PASSWORD",
            "secrets.HARNESS_ACTIONS_TOKEN",
        )
    }

    @Test
    fun `readme documents the narrowly scoped harness actions token`() {
        val readme = Files.readString(Path.of("README.md"))

        assertThat(readme).contains(
            "HARNESS_ACTIONS_TOKEN",
            "robinjoon-homelab/Simple-K3S-Herness",
            "`Actions: write`",
        )
    }

    @Test
    fun `deployment documentation distinguishes token permissions and declared release status`() {
        val deployment = Files.readString(Path.of("docs/deployment.md"))

        assertThat(deployment).contains("`Actions: write`", "Contents", "60회", "10초")
        assertThat(deployment).contains("Argo CD 동기화, Pod 준비 상태, DNS와 TLS를 검증한 결과는 아니다")
    }
}
