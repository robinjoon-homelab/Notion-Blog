package xyz.robinjoon.notionblog.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class ArchitectureRulesTest {
    private val badRules = ArchitectureRules("$FIXTURES.bad", "BlogApplication")

    @TestFactory
    fun `architecture rules reject intentional violations`(): List<DynamicTest> =
        negativeCases().map { (rule, expectedClasses) ->
            DynamicTest.dynamicTest(rule.description) {
                val result = rule.evaluate(badClasses)
                assertThat(result.hasViolation()).isTrue()
                assertThat(result.failureReport.details.joinToString("\n")).contains(*expectedClasses.toTypedArray())
            }
        }

    @TestFactory
    fun `adapters cannot declare or inherit transactions`(): List<DynamicTest> =
        listOf(
            "ClassTransactional",
            "MethodTransactional",
            "ComposedClassTransactional",
            "ComposedMethodTransactional",
            "InheritedClassTransactional",
            "InheritedMethodTransactional",
            "InterfaceClassTransactional",
            "InterfaceMethodTransactional",
        ).flatMap { prefix ->
            listOf("Controller", "Repository", "Scheduler", "Source", "Catalog", "Reporter").map { "$prefix$it" }
        }.map { name ->
            DynamicTest.dynamicTest(name) {
                val target = badClasses.that(DescribedPredicate.describe(name) { it.simpleName == name })
                assertThat(target).hasSize(1)

                val result = badRules.adapterTransactions.evaluate(target)
                assertThat(result.hasViolation()).isTrue()
                assertThat(result.failureReport.details.joinToString("\n")).contains(name)
            }
        }

    @TestFactory
    fun `valid constructor injection ports and pure types pass all rules`(): List<DynamicTest> =
        ArchitectureRules("$FIXTURES.good", "BlogApplication").all().map { rule ->
            DynamicTest.dynamicTest(rule.description) { rule.check(goodClasses) }
        }

    @Test
    fun `class outside the root package cannot escape location checks`() {
        val outsideClasses = ClassFileImporter().importPackages("$FIXTURES.outside")
        val result = badRules.approvedLocations.evaluate(outsideClasses)
        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details.joinToString()).contains("EscapedClass")
    }

    @Test
    fun `positive fixtures include Kotlin generated helpers and data contracts`() {
        assertThat(goodClasses.map { it.name })
            .contains(
                "$FIXTURES.good.application.port.input.CompanionPort\$Companion",
                "$FIXTURES.good.application.port.input.CreateCommand",
                "$FIXTURES.good.application.port.input.CreateResult",
            )
    }

    @Test
    fun `URI values are allowed without permitting URL and socket dependencies`() {
        val uriValues =
            goodClasses.that(
                DescribedPredicate.describe("URI value fixtures") { it.simpleName in setOf("UriValue", "ResourceLocation") },
            )
        assertThat(uriValues).hasSize(2)
        ArchitectureRules("$FIXTURES.good", "BlogApplication").noCoreIo.check(uriValues)

        val networkModel = badClasses.that(DescribedPredicate.describe("network model") { it.simpleName == "NetworkModel" })
        assertThat(networkModel).hasSize(1)
        val result = badRules.noCoreIo.evaluate(networkModel)
        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details.joinToString("\n")).contains("java.net.URL", "java.net.Socket")
    }

    @Test
    fun `a disconnected domain cycle is rejected among otherwise acyclic packages`() {
        val domainClasses =
            badClasses.that(
                DescribedPredicate.describe("domain fixtures") { it.packageName.startsWith("$FIXTURES.bad.domain") },
            )
        assertThat(domainClasses.map { it.simpleName }).contains("Alpha", "Beta", "IoDomain", "ApplicationDependency")

        val result = badRules.acyclicPackages.evaluate(domainClasses)
        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details.joinToString("\n")).contains("Alpha", "Beta")
    }

    private fun negativeCases(): List<Pair<ArchRule, List<String>>> =
        listOf(
            badRules.approvedLocations to listOf("UnassignedClass", "HelperKt"),
            badRules.pureDomain to listOf("ApplicationDependency", "MisplacedController", "TransactionalDomain"),
            badRules.pureApplication to
                listOf(
                    "BadService",
                    "TransactionalInputPort",
                    "TransactionalOutputPort",
                    "ServiceStereotype",
                    "TransactionTemplateService",
                    "TransactionManagerService",
                ),
            badRules.noCoreIo to listOf("IoDomain", "BadService", "java.io.File", "java.net.URL", "java.net.Socket", "java.sql.Connection"),
            badRules.independentPorts to listOf("BadInput", "BadOutput"),
            badRules.independentModels to listOf("BadServiceModel"),
            badRules.inboundBoundary to listOf("BadController", "ServiceDependentScheduler"),
            badRules.outboundBoundary to listOf("BadPersistence"),
            badRules.independentAdapters to
                listOf("BadController", "BadPersistence", "PersistenceDependentSource", "PersistenceDependentReporter"),
            badRules.portContracts to
                listOf("ConcreteUseCase", "ConcreteRepository", "ConcreteSource", "ConcreteCatalog", "ConcreteReporter"),
            badRules.constructorInjection to
                listOf("FieldInjected", "MethodInjected", "ResourceFieldInjected", "ResourceMethodInjected", "ValueFieldInjected"),
            badRules.controllerRoles to listOf("MisplacedController", "WrongEndpoint", "ComposedEndpoint", "MvcEndpoint"),
            badRules.controllerNames to listOf("MisplacedController"),
            badRules.configurationRoles to listOf("MisplacedConfig", "WrongConfiguration"),
            badRules.repositoryRoles to listOf("MisplacedRepository", "MisplacedStore"),
            badRules.repositoryRoles to listOf("MisplacedSource", "StoredSource", "ClasspathSource"),
            badRules.repositoryRoles to listOf("MisplacedCatalog", "StoredCatalog", "NotionAssetCatalog"),
            badRules.repositoryRoles to listOf("MisplacedReporter", "PersistenceReporter", "NotionReporter", "PresentationReporter"),
            badRules.repositoryTransactionOwnership to listOf("ExplicitTransactionRepository", "transactions"),
            badRules.adapterTransactionInfrastructure to
                listOf("TransactionTemplateRepository", "TransactionManagerRepository", "TransactionTemplateReporter"),
            badRules.isolatedExposed to listOf("ExposedResponse", "GenericExposedResponse"),
            badRules.acyclicPackages to listOf("BadController", "BadPersistence"),
        )

    companion object {
        private const val FIXTURES = "architecturefixtures"
        private val badClasses = ClassFileImporter().importPackages("$FIXTURES.bad")
        private val goodClasses = ClassFileImporter().importPackages("$FIXTURES.good")
    }
}
