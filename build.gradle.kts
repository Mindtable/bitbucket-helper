import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.jooq.codegen)
    application
}

group = "com.mindtable.bitbuckethelper"
version = "0.1.0"

repositories {
    mavenCentral()
}

val liquibaseRuntime by configurations.creating

val migrationDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
val migrationName = Regex("^V[0-9]{4}__[a-z0-9]+(?:_[a-z0-9]+)*\\.xml$")
val jooqCodegenDirectory = layout.buildDirectory.dir("jooq-codegen")
val jooqCodegenDatabase = jooqCodegenDirectory.map { it.file("bitbucket-helper.sqlite") }
val generatedJooqDirectory = layout.buildDirectory.dir("generated/sources/jooq/main/kotlin")
val generatedBitbucketDirectory = layout.buildDirectory.dir("generated/sources/bitbucket/src/main/kotlin")
val apiV1Contract = layout.projectDirectory.file("openapi/api-v1.yaml")
val apiV1KotlinConfig = layout.projectDirectory.file("openapi/generator/kotlin-models.yaml")
val apiV1TypeScriptConfig = layout.projectDirectory.file("openapi/generator/typescript-fetch.yaml")
val apiV1KotlinCandidate = layout.buildDirectory.dir("openapi/api-v1/kotlin-candidate")
val apiV1TypeScriptCandidate = layout.buildDirectory.dir("openapi/api-v1/typescript-candidate")
val apiV1KotlinCommitted = layout.projectDirectory.dir("src/generated/api-v1/kotlin")
val apiV1TypeScriptCommitted = layout.projectDirectory.dir("web/src/generated/api-v1")

fun normalizeGeneratedText(directory: File) {
    directory.walkTopDown()
        .filter(File::isFile)
        .forEach { file ->
            val original = file.readText()
            val normalized = original
                .replace("\r\n", "\n")
                .split('\n')
                .joinToString("\n", transform = String::trimEnd)
                .trimEnd('\n') + "\n"
            if (normalized != original) file.writeText(normalized)
        }
}

kotlin { jvmToolchain(25) }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedJooqDirectory)
    kotlin.srcDir(generatedBitbucketDirectory)
    kotlin.srcDir(apiV1KotlinCommitted.dir("src/main/kotlin"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.mindtable.bitbuckethelper.bootstrap.MainKt")
}

ktor {
    fatJar {
        archiveFileName.set("bitbucket-helper-${project.version}-all.jar")
    }
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation(libs.quartz)
    implementation(libs.liquibase.core)
    implementation(libs.jooq)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    liquibaseRuntime(libs.liquibase.core)
    liquibaseRuntime("info.picocli:picocli:4.7.7")
    liquibaseRuntime(libs.sqlite.jdbc)
    jooqCodegen(libs.sqlite.jdbc)
}

val validateMigrationNames by tasks.registering {
    group = "verification"
    inputs.dir(migrationDirectory)
    doLast {
        val candidates = migrationDirectory.asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name != "AGENTS.md" }
        val invalid = candidates.map { it.name }.filterNot(migrationName::matches)
        check(invalid.isEmpty()) { "Invalid migration filenames: ${invalid.sorted().joinToString()}" }
        val versions = candidates.map { it.name.substring(1, 5) }
        check("0000" !in versions) { "Migration version V0000 is forbidden" }
        val duplicates = versions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "Duplicate migration versions: ${duplicates.sorted().joinToString()}" }
    }
}

val prepareJooqCodegenDatabase by tasks.registering(JavaExec::class) {
    group = "jooq"
    dependsOn(validateMigrationNames)
    classpath = liquibaseRuntime
    mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
    args(
        "--search-path=/",
        "--url=jdbc:sqlite:${jooqCodegenDatabase.get().asFile.absolutePath}",
        "--driver=org.sqlite.JDBC",
        "--changelog-file=${layout.projectDirectory.file("src/main/resources/db/changelog/db.changelog-master.xml").asFile.absolutePath}",
        "update",
    )
    doFirst {
        Files.createDirectories(jooqCodegenDirectory.get().asFile.toPath())
        Files.deleteIfExists(jooqCodegenDatabase.get().asFile.toPath())
    }
}

jooq {
    configuration {
        jdbc {
            driver = "org.sqlite.JDBC"
            url = "jdbc:sqlite:${jooqCodegenDatabase.get().asFile.absolutePath}"
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database { name = "org.jooq.meta.sqlite.SQLiteDatabase" }
            generate {
                isDeprecated = false
                isRecords = true
                isImmutablePojos = false
            }
            target {
                packageName = "com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated"
                directory = generatedJooqDirectory.get().asFile.path
            }
        }
    }
}

tasks.named("jooqCodegen") {
    dependsOn(prepareJooqCodegenDatabase)
}

val canonicalBitbucketOpenApiUrl = "https://api.bitbucket.org/swagger.json"
val canonicalBitbucketOpenApi = layout.projectDirectory.file("specs/bitbucket-cloud/openapi.json")
val canonicalBitbucketOpenApiMetadata = layout.projectDirectory.file("specs/bitbucket-cloud/README.md")

val updateBitbucketOpenApiSpec by tasks.registering {
    group = "maintenance"
    description = "Explicitly downloads and validates the pinned Bitbucket Cloud OpenAPI snapshot."
    doLast {
        val updateDirectory = layout.buildDirectory.dir("openapi-update").get().asFile.toPath()
        Files.createDirectories(updateDirectory)
        val candidate = updateDirectory.resolve("openapi.json.download")
        URI.create(canonicalBitbucketOpenApiUrl).toURL().openStream().use { input ->
            Files.copy(input, candidate, REPLACE_EXISTING)
        }

        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(candidate.toFile()) as Map<String, Any?>
        val swaggerVersion = root["swagger"] as? String
        check(swaggerVersion == "2.0") {
            "Candidate must be Swagger/OpenAPI 2.0; found: $swaggerVersion"
        }
        val paths = root["paths"] as? Map<*, *>
        val userPath = paths?.get("/user") as? Map<*, *>
        check(userPath?.get("get") is Map<*, *>) {
            "Candidate must contain GET /user"
        }

        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(candidate))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val snapshotPath = canonicalBitbucketOpenApi.asFile.toPath()
        Files.createDirectories(snapshotPath.parent)
        Files.move(candidate, snapshotPath, ATOMIC_MOVE, REPLACE_EXISTING)
        canonicalBitbucketOpenApiMetadata.asFile.writeText(
            """# Bitbucket Cloud OpenAPI Snapshot

- Canonical source: `$canonicalBitbucketOpenApiUrl`
- Retrieved (UTC): `${LocalDate.now(ZoneOffset.UTC)}`
- SHA-256: `$checksum`
- Source format: `Swagger/OpenAPI 2.0`
- OpenAPI Generator validation version: `7.24.0`
- Generated client library: `jvm-ktor`
- Reduced-spec compatibility: `definitions.object.additionalProperties` omitted; canonical snapshot unchanged

## Update and review procedure

1. Run `./gradlew updateBitbucketOpenApiSpec` explicitly; normal builds never run it.
2. Review the complete `openapi.json` diff, especially `GET /user` and recursively referenced schemas.
3. Run `./gradlew clean check` to regenerate and compile the selected client.
4. Inspect `build/generated/sources/bitbucket/src/main/kotlin` before committing the snapshot and metadata together.
""",
        )
    }
}

fun collectSwaggerRefs(node: Any?, destination: MutableSet<String>) {
    when (node) {
        is Map<*, *> -> node.forEach { (key, value) ->
            if (key == "\$ref" && value is String) {
                val supportedPrefixes = setOf("#/definitions/", "#/parameters/", "#/responses/")
                check(supportedPrefixes.any { value.startsWith(it) }) {
                    "External or unsupported OpenAPI reference is not allowed: $value"
                }
                destination += value
            } else {
                collectSwaggerRefs(value, destination)
            }
        }
        is Iterable<*> -> node.forEach { collectSwaggerRefs(it, destination) }
    }
}

fun swaggerAllOfParentName(definition: Map<String, Any?>): String? {
    val allOf = definition["allOf"] as? List<*> ?: return null
    val reference = (allOf.firstOrNull() as? Map<*, *>)?.get("\$ref") as? String ?: return null
    val prefix = "#/definitions/"
    return reference.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
}

fun mergeSwaggerSchemas(
    destination: LinkedHashMap<String, Any?>,
    source: Map<String, Any?>,
) {
    source.forEach { (key, value) ->
        when (key) {
            "allOf", "discriminator", "\$ref" -> Unit
            "properties" -> {
                @Suppress("UNCHECKED_CAST")
                val sourceProperties = value as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val destinationProperties = destination.getOrPut("properties") {
                    linkedMapOf<String, Any?>()
                } as MutableMap<String, Any?>
                destinationProperties.putAll(sourceProperties)
            }
            "required" -> {
                @Suppress("UNCHECKED_CAST")
                val sourceRequired = value as List<String>
                @Suppress("UNCHECKED_CAST")
                val destinationRequired = destination.getOrPut("required") {
                    mutableListOf<String>()
                } as MutableList<String>
                sourceRequired.filterNot(destinationRequired::contains).forEach(destinationRequired::add)
            }
            else -> destination[key] = value
        }
    }
}

fun flattenSwaggerAllOf(
    definition: Map<String, Any?>,
    definitions: Map<String, Any?>,
): LinkedHashMap<String, Any?> {
    val flattened = linkedMapOf<String, Any?>()
    val allOf = definition["allOf"] as? List<*>
    if (allOf == null) {
        mergeSwaggerSchemas(flattened, definition)
        return flattened
    }
    allOf.forEach { component ->
        @Suppress("UNCHECKED_CAST")
        val componentObject = component as Map<String, Any?>
        val parentName = (componentObject["\$ref"] as? String)
            ?.removePrefix("#/definitions/")
        @Suppress("UNCHECKED_CAST")
        val componentSchema = if (parentName != null) {
            definitions.getValue(parentName) as Map<String, Any?>
        } else {
            componentObject
        }
        mergeSwaggerSchemas(flattened, flattenSwaggerAllOf(componentSchema, definitions))
    }
    mergeSwaggerSchemas(flattened, definition.filterKeys { it != "allOf" })
    return flattened
}

fun normalizeChainedDiscriminatorAllOf(definitions: MutableMap<String, Any?>) {
    val incompatibleDefinitions = definitions.mapNotNull { (name, value) ->
        @Suppress("UNCHECKED_CAST")
        val definition = value as Map<String, Any?>
        val parentName = swaggerAllOfParentName(definition) ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val parent = definitions[parentName] as? Map<String, Any?> ?: return@mapNotNull null
        val discriminatorBaseName = swaggerAllOfParentName(parent) ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val discriminatorBase = definitions[discriminatorBaseName] as? Map<String, Any?>
            ?: return@mapNotNull null
        name.takeIf { discriminatorBase.containsKey("discriminator") }
    }
    incompatibleDefinitions.forEach { name ->
        @Suppress("UNCHECKED_CAST")
        val definition = definitions.getValue(name) as Map<String, Any?>
        definitions[name] = flattenSwaggerAllOf(definition, definitions)
    }
}

data class SelectedBitbucketOperation(
    val path: String,
    val method: String,
    val operationId: String,
)

val selectedBitbucketOperations = listOf(
    SelectedBitbucketOperation("/user", "get", "getCurrentUser"),
    SelectedBitbucketOperation("/workspaces/{workspace}", "get", "getWorkspace"),
    SelectedBitbucketOperation("/repositories/{workspace}/{repo_slug}", "get", "getRepository"),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests",
        "get",
        "listAuthoredOpenPullRequests",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}",
        "get",
        "getPullRequest",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/refs/branches",
        "get",
        "listDestinationBranches",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/merge-base/{revspec}",
        "get",
        "getMergeBase",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/file-conflicts/{spec}",
        "get",
        "listFileConflicts",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/effective-default-reviewers",
        "get",
        "listEffectiveDefaultReviewers",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/statuses",
        "get",
        "listPullRequestStatuses",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/tasks",
        "get",
        "listPullRequestTasks",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/activity",
        "get",
        "listPullRequestActivity",
    ),
    SelectedBitbucketOperation(
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/comments/{comment_id}",
        "get",
        "getPullRequestComment",
    ),
)

val prepareBitbucketOpenApi by tasks.registering {
    val source = canonicalBitbucketOpenApi
    val target = layout.buildDirectory.file("openapi/bitbucket-current-user.json")
    inputs.file(source)
    outputs.file(target)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(source.asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val paths = root.getValue("paths") as Map<String, Any?>

        val pending = linkedSetOf<String>()
        val processed = linkedSetOf<String>()
        val copied = linkedMapOf<String, MutableMap<String, Any?>>()
        val selectedPaths = linkedMapOf<String, Any?>()
        val selectedTags = linkedSetOf<String>()
        selectedBitbucketOperations.forEach { selection ->
            @Suppress("UNCHECKED_CAST")
            val canonicalPath = paths.getValue(selection.path) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val selectedOperation = LinkedHashMap(
                canonicalPath.getValue(selection.method) as Map<String, Any?>,
            ).apply {
                this["operationId"] = selection.operationId
                if (selection.operationId == "listAuthoredOpenPullRequests") {
                    @Suppress("UNCHECKED_CAST")
                    val parameters = (this["parameters"] as? List<Map<String, Any?>>)
                        .orEmpty()
                        .map(::LinkedHashMap)
                        .toMutableList()
                    parameters += linkedMapOf(
                        "name" to "q",
                        "in" to "query",
                        "description" to
                            "Query string to narrow down the response as supported by this endpoint's filtering contract.",
                        "required" to false,
                        "type" to "string",
                    )
                    this["parameters"] = parameters
                }
                @Suppress("UNCHECKED_CAST")
                val operationTags = this["tags"] as? List<String>
                if (operationTags != null) {
                    this["tags"] = operationTags.take(1).map { tag ->
                        if (tag == "Pullrequests") "Pull requests" else tag
                    }.also(selectedTags::addAll)
                }
            }
            val selectedPath = linkedMapOf<String, Any?>(selection.method to selectedOperation)
            canonicalPath["parameters"]?.let { parameters ->
                selectedPath["parameters"] = parameters
                collectSwaggerRefs(parameters, pending)
            }
            selectedPaths[selection.path] = selectedPath
            collectSwaggerRefs(selectedOperation, pending)
        }
        while (true) {
            val ref = pending.firstOrNull { it !in processed } ?: break
            processed += ref
            val segments = ref.removePrefix("#/").split('/')
            check(segments.size == 2) { "Unsupported Swagger reference: $ref" }
            @Suppress("UNCHECKED_CAST")
            val referencedEntries = root.getValue(segments[0]) as Map<String, Any?>
            val value = referencedEntries.getValue(segments[1])
            copied.getOrPut(segments[0]) { linkedMapOf() }[segments[1]] = value
            collectSwaggerRefs(value, pending)
        }

        @Suppress("UNCHECKED_CAST")
        val generatedObject = copied.getValue("definitions").getValue("object") as Map<String, Any?>
        check(generatedObject["additionalProperties"] == true) {
            "Expected canonical definitions.object.additionalProperties to be true"
        }
        copied.getValue("definitions")["object"] = LinkedHashMap(generatedObject).apply {
            remove("additionalProperties")
        }
        normalizeChainedDiscriminatorAllOf(copied.getValue("definitions"))

        val reduced = linkedMapOf<String, Any?>(
            "swagger" to root.getValue("swagger"),
            "info" to root.getValue("info"),
            "host" to root["host"],
            "basePath" to root["basePath"],
            "schemes" to root["schemes"],
            "consumes" to root["consumes"],
            "produces" to root["produces"],
            "security" to root["security"],
            "tags" to (root["tags"] as? List<*>)?.filter {
                val tag = (it as? Map<*, *>)?.get("name")
                tag in selectedTags || tag == "Pullrequests" && "Pull requests" in selectedTags
            }?.map { tag ->
                @Suppress("UNCHECKED_CAST")
                LinkedHashMap(tag as Map<String, Any?>).apply {
                    if (this["name"] == "Pullrequests") this["name"] = "Pull requests"
                }
            },
            "paths" to selectedPaths,
            "definitions" to copied["definitions"],
            "parameters" to copied["parameters"],
            "responses" to copied["responses"],
            "securityDefinitions" to root["securityDefinitions"],
        ).filterValues { it != null }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(reduced)))
        }
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(layout.buildDirectory.file("openapi/bitbucket-current-user.json").get().asFile.path)
    outputDir.set(layout.buildDirectory.dir("generated/sources/bitbucket").get().asFile.path)
    templateDir.set(layout.projectDirectory.dir("specs/bitbucket-cloud/openapi-templates"))
    packageName.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated")
    apiPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api")
    modelPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model")
    invokerPackage.set("com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure")
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "serializationLibrary" to "jackson",
            "useCoroutines" to "true",
            "omitGradleWrapper" to "true",
            "hideGenerationTimestamp" to "true",
        ),
    )
}

tasks.named("openApiGenerate") {
    dependsOn(prepareBitbucketOpenApi)
    doFirst { delete(layout.buildDirectory.dir("generated/sources/bitbucket")) }
}

val validateApiV1 by tasks.registering(ValidateTask::class) {
    group = "verification"
    description = "Validates the canonical product API v1 OpenAPI document."
    inputSpec.set(apiV1Contract.asFile.path)
    recommend.set(true)
}

val generateApiV1KotlinCandidate by tasks.registering(GenerateTask::class) {
    group = "code generation"
    description = "Generates a disposable candidate of the product API v1 Kotlin models."
    dependsOn(validateApiV1)
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(apiV1Contract.asFile.path)
    outputDir.set(apiV1KotlinCandidate.get().asFile.path)
    configFile.set(apiV1KotlinConfig.asFile.path)
    templateDir.set(layout.projectDirectory.dir("openapi/generator/kotlin-templates"))
    globalProperties.set(
        mapOf(
            "models" to "",
            "modelDocs" to "false",
            "modelTests" to "false",
            "apis" to "false",
            "supportingFiles" to "false",
        ),
    )
    typeMappings.set(
        mapOf(
            "DateTime" to "kotlin.String",
            "URI" to "kotlin.String",
        ),
    )
    doFirst { delete(apiV1KotlinCandidate) }
    doLast { normalizeGeneratedText(apiV1KotlinCandidate.get().asFile) }
}

val generateApiV1TypeScriptCandidate by tasks.registering(GenerateTask::class) {
    group = "code generation"
    description = "Generates a disposable candidate of the product API v1 TypeScript fetch client."
    dependsOn(validateApiV1)
    generatorName.set("typescript-fetch")
    inputSpec.set(apiV1Contract.asFile.path)
    outputDir.set(apiV1TypeScriptCandidate.get().asFile.path)
    configFile.set(apiV1TypeScriptConfig.asFile.path)
    templateDir.set(layout.projectDirectory.dir("openapi/generator/typescript-templates"))
    globalProperties.set(
        mapOf(
            "models" to "",
            "modelDocs" to "false",
            "modelTests" to "false",
            "apis" to "",
            "apiDocs" to "false",
            "apiTests" to "false",
            "supportingFiles" to "",
        ),
    )
    typeMappings.set(mapOf("DateTime" to "string"))
    doFirst { delete(apiV1TypeScriptCandidate) }
    doLast { normalizeGeneratedText(apiV1TypeScriptCandidate.get().asFile) }
}

fun directoryDigest(directory: File): Map<String, String> {
    check(directory.isDirectory) { "Generated directory is missing: $directory" }
    return directory.walkTopDown()
        .filter(File::isFile)
        .associate { file ->
            val relativePath = file.relativeTo(directory).invariantSeparatorsPath
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            relativePath to digest
        }
        .toSortedMap()
}

val syncApiV1Generated by tasks.registering {
    group = "code generation"
    description = "Replaces committed product API v1 artifacts with deterministic candidates."
    dependsOn(generateApiV1KotlinCandidate, generateApiV1TypeScriptCandidate)
    doLast {
        sync {
            from(apiV1KotlinCandidate)
            into(apiV1KotlinCommitted)
        }
        sync {
            from(apiV1TypeScriptCandidate)
            into(apiV1TypeScriptCommitted)
        }
        // Gradle's default excludes omit .gitignore from Copy/Sync inputs.
        Files.copy(
            apiV1TypeScriptCandidate.get().asFile.toPath().resolve(".gitignore"),
            apiV1TypeScriptCommitted.asFile.toPath().resolve(".gitignore"),
            REPLACE_EXISTING,
        )
    }
}

val verifyApiV1Generated by tasks.registering {
    group = "verification"
    description = "Fails when committed product API v1 artifacts differ byte-for-byte from generation."
    dependsOn(generateApiV1KotlinCandidate, generateApiV1TypeScriptCandidate)
    doLast {
        val comparisons = listOf(
            "Kotlin" to (apiV1KotlinCandidate.get().asFile to apiV1KotlinCommitted.asFile),
            "TypeScript" to (apiV1TypeScriptCandidate.get().asFile to apiV1TypeScriptCommitted.asFile),
        )
        comparisons.forEach { (surface, directories) ->
            val (candidate, committed) = directories
            check(directoryDigest(candidate) == directoryDigest(committed)) {
                "$surface product API v1 generated output is stale; run ./gradlew syncApiV1Generated"
            }
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn("jooqCodegen", "openApiGenerate")
}

tasks.named("compileTestKotlin") {
    dependsOn("jooqCodegen")
}

tasks.named("check") {
    dependsOn(validateMigrationNames)
    dependsOn(verifyApiV1Generated)
}

tasks.test {
    dependsOn(tasks.named("buildFatJar"))
    dependsOn(prepareBitbucketOpenApi)
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
