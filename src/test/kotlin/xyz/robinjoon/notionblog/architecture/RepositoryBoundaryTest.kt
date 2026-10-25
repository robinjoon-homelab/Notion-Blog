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
        assertThat(dockerfile.substringAfter("FROM eclipse-temurin:25-jre-noble")).doesNotContain("25-jdk", "./gradlew", "bootJar")
        assertThat(dockerfile).contains("RuntimeTrust sanitize", "RuntimeTrust roots")
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
            "APP_NAME: \${{ vars.HOMELAB_APP_NAME }}",
            "REGISTRY_HOST: \${{ vars.HOMELAB_REGISTRY_HOST }}",
            "REGISTRY_IMAGE: \${{ vars.HOMELAB_REGISTRY_IMAGE }}",
            "run: python3 scripts/deploy.py metadata",
        )
        assertThat(workflow).contains("run: docker push \"\$IMAGE\"")
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
        val deployJob = workflow.substringAfter("  deploy:")
        val artifactDownload = deployJob.indexOf("- name: Download the verified application image")
        val imagePushStep = deployJob.indexOf("run: docker push")
        val releaseRequestStep = deployJob.indexOf("run: python3 scripts/deploy.py apply")

        assertThat(artifactDownload).isGreaterThanOrEqualTo(0)
        assertThat(deployJob.indexOf("docker load -i build/application-image.tar")).isGreaterThan(artifactDownload)
        assertThat(imagePushStep).isGreaterThanOrEqualTo(0)
        assertThat(releaseRequestStep).isGreaterThan(imagePushStep)
        assertThat(workflow).contains(
            "group: homelab-deploy",
            "cancel-in-progress: false",
            "GH_TOKEN: \${{ github.token }}",
            "IMAGE: \${{ steps.metadata.outputs.image }}",
            "IMAGE_TAG: \${{ steps.metadata.outputs.tag }}",
            "run: python3 scripts/deploy.py apply",
            "path: build/application-image.tar",
            "path: build/",
            "name: application-image-\${{ github.sha }}",
            "test \"\$revision\" = \"\$GITHUB_SHA\"",
            "bash scripts/verify-runtime-image.sh",
        )
        assertThat(deployJob).doesNotContain(":latest", "if: always()", "continue-on-error: true", "./gradlew", "docker/build-push-action@")
        assertThat(workflow.split("docker/build-push-action@")).hasSize(2)
    }

    @Test
    fun `ci validates the configured deployment identity before requesting any platform credentials`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val deployJob = workflow.substringAfter("  deploy:")
        val metadata = deployJob.indexOf("run: python3 scripts/deploy.py metadata")
        val credentialLoad = deployJob.indexOf("load-ci-secrets@")

        assertThat(metadata).isGreaterThanOrEqualTo(0)
        assertThat(credentialLoad).isGreaterThan(metadata)
        assertThat(deployJob).contains("id: metadata", "APP_NAME: \${{ vars.HOMELAB_APP_NAME }}")
        assertThat(deployJob).contains(
            "needs.verify.outputs.deployable == 'true'",
            "github.event_name == 'push' || github.event_name == 'workflow_dispatch'",
            "github.ref == 'refs/heads/main' || github.ref == 'refs/heads/master'",
        )
        assertThat(deployJob).doesNotContain("APP_NAME: notion-blog", "github.repository_id")
    }

    @Test
    fun `ci checks pull request source commits and rejects formatting changes`() {
        val workflow = Files.readString(Path.of(".github/workflows/ci.yml"))
        val verification = workflow.substringBefore("  deploy:")

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
        val deployJob = workflow.substringAfter("  deploy:")
        val registryLoad = deployJob.indexOf("- name: Load registry credentials through GitHub OIDC")
        val harnessLoad = deployJob.indexOf("- name: Load harness credentials through GitHub OIDC")
        val validation = deployJob.indexOf("- name: Validate platform credentials")

        assertThat(registryLoad).isGreaterThanOrEqualTo(0)
        assertThat(harnessLoad).isGreaterThan(registryLoad)
        assertThat(validation).isGreaterThan(harnessLoad)
        assertThat(deployJob).contains("id-token: write", "app: zot", "app: harness")
        assertThat(deployJob.split("load-ci-secrets@v1.0.0")).hasSize(3)
        assertThat(workflow.substringBefore("  deploy:")).doesNotContain("id-token: write")
        assertThat(workflow).doesNotContain(
            "secrets.HOMELAB_REGISTRY_USERNAME",
            "secrets.HOMELAB_REGISTRY_PASSWORD",
            "secrets.HARNESS_ACTIONS_TOKEN",
        )
    }
}
